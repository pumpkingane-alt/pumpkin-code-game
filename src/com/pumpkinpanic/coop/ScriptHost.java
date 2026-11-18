package com.pumpkinpanic.coop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

import com.pumpkinpanic.coop.ScriptValues.*;

/**
 * Runs the player's script next to the game.
 * Every script thread is a real Java thread, but only ONE thread (the game or exactly one script thread)
 * runs at any moment: control is handed over with semaphores, so scripts never race with the game.
 * A script thread gives control back when it uses up its op budget for this frame, sleeps, or waits for a character task.
 * Java 7 compatible.
 */
final class ScriptHost implements ScriptInterp.Output {
    /** What scripts need from the running game (implemented by GameView / the tests). */
    interface Link {
        World world();
        World.Player me();
        boolean authority();                 // host or solo: actions are applied directly
        void cmdAct();                       // client: ask the host to act / buy / select / emote
        void cmdBuy(int item);
        void cmdSel(int sel);
        void cmdEmote(int e);
        void onStatus(int status, ScriptError err);     // UI notification (status change / error)
    }

    static final int IDLE = 0, RUNNING = 1, FINISHED = 2, ERROR = 3, STOPPED = 4;
    static final int LOG_PRINT = 0, LOG_WARN = 1, LOG_ERROR = 2, LOG_INFO = 3;

    static final class LogLine {
        final int kind; final String text; final long at;
        LogLine(int k, String t) { kind = k; text = t; at = System.currentTimeMillis(); }
    }

    /** One script thread. */
    final class ThreadCtx extends ScriptInterp.Ctx implements Runnable, ScriptValues.ScriptObject {
        final int id; int priority;
        Thread thread; final Semaphore wake = new Semaphore(0);
        boolean started, finished;
        boolean sleeping; double sleepLeft;
        ScriptTask waiting; ThreadCtx joining;
        Object entry; Object[] entryArgs;            // function to run
        Object result; ScriptError error;
        ThreadCtx(int id, String name) { this.id = id; this.name = name; }

        void yieldBudget() { ScriptHost.this.handBack(this); }
        void sleep(double seconds) { sleeping = true; sleepLeft = Math.max(0, seconds); ScriptHost.this.handBack(this); }
        Object waitTask(Object task) {
            ScriptTask t = (ScriptTask) task;
            t.owner = this; t.prio = priority;
            interp.flushPrint();
            pilot.submit(t);
            waiting = t;
            try { ScriptHost.this.handBack(this); } finally { waiting = null; }
            return t.result;
        }

        public void run() {
            wake.acquireUninterruptibly();
            try {
                if (!killed) {
                    ScriptValues.Frame f = new ScriptValues.Frame(interp.globals, this, "<" + name + ">", "main", null);
                    if (entry == null) { result = null; interp.runProgram(program, this); }
                    else result = interp.call(entry, entryArgs, new String[0], new Object[0], f, null);
                }
            } catch (ScriptError e) { error = e; }
            catch (ScriptError.Kill k) { /* stopped */ }
            catch (StackOverflowError so) { error = new ScriptError("RecursionError", "maximum recursion depth exceeded"); }
            catch (OutOfMemoryError om) { error = new ScriptError("MemoryError", "the script used too much memory"); }
            catch (Throwable t) { error = new ScriptError("InternalError", String.valueOf(t)); }
            finally { finished = true; sched.release(); }
        }

        // ScriptObject: the handle returned by spawn()
        public Object getAttr(String n) {
            if (n.equals("alive")) return Boolean.valueOf(!finished);
            if (n.equals("name")) return name;
            if (n.equals("id")) return Long.valueOf(id);
            if (n.equals("result")) return result;
            return null;
        }
        public boolean hasAttr(String n) { return n.equals("alive") || n.equals("name") || n.equals("id") || n.equals("result"); }
        public String typeName() { return "Thread"; }
        public String[] attrNames() { return new String[] {"alive", "name", "id", "result"}; }
        @Override public String toString() { return "<Thread " + name + (finished ? " done>" : " running>"); }
    }

    final Link link;
    final ScriptPilot pilot = new ScriptPilot(this);
    ScriptInterp interp;
    ScriptAst.Program program;
    ScriptInterp.Loader loader;
    final ArrayList<ThreadCtx> threads = new ArrayList<ThreadCtx>();
    final Semaphore sched = new Semaphore(0);
    final ArrayList<LogLine> log = new ArrayList<LogLine>();
    final HashMap<String, Object> memory = new HashMap<String, Object>();      // save()/load(): survives restarts
    int status = IDLE;
    ScriptError error;
    String scriptName = "main";
    boolean stopRequested;
    int nextId = 1;
    long logVersion;
    String lastFail;                              // reason of the last failed action (last_error())
    private ScriptInterp checkInterp;
    private final HashMap<String, long[]> warnState = new HashMap<String, long[]>();
    float runTime;                                // seconds since the script started

    ScriptHost(Link link) { this.link = link; }

    // ------------------------------------------------------------ console
    public void print(int kind, String text) { log(kind, text); }

    void log(int kind, String text) {
        synchronized (log) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) { log.add(new LogLine(kind, lines[i])); }
            while (log.size() > 400) log.remove(0);
            logVersion++;
        }
    }
    void clearLog() { synchronized (log) { log.clear(); logVersion++; } }

    /** Warn at most once every few seconds for the same message. */
    void warnOnce(String key, String text) {
        long now = System.currentTimeMillis();
        long[] st = warnState.get(key);
        if (st != null && now - st[0] < 4000) { st[1]++; return; }
        if (st != null && st[1] > 0) text += "  (x" + (st[1] + 1) + ")";
        warnState.put(key, new long[] {now, 0});
        log(LOG_WARN, text);
    }

    // ------------------------------------------------------------ checking / starting
    /** A throw-away interpreter that knows every API name (used by the static checker and by start()). */
    ScriptInterp knownInterp() {
        if (checkInterp == null) { checkInterp = new ScriptInterp(this); ScriptApi.install(checkInterp, this); }
        return checkInterp;
    }

    /** Syntax + semantic check without running. Returns problems (errors first). */
    ArrayList<ScriptError> check(String source, String name) {
        return ScriptChecker.check(source, name, knownInterp(), loader);
    }

    /** Start (or restart) the script. Returns the problems found; empty = running. */
    ArrayList<ScriptError> start(String source, String name) {
        stop(false);
        scriptName = name; stopRequested = false; error = null; lastFail = null; runTime = 0;
        warnState.clear(); pilot.reset();
        ArrayList<ScriptError> probs = check(source, name);
        boolean hasError = false;
        for (int i = 0; i < probs.size(); i++) if (probs.get(i).severity == ScriptError.SEV_ERROR) hasError = true;
        if (hasError) {
            for (int i = 0; i < probs.size(); i++) log(probs.get(i).severity == ScriptError.SEV_ERROR ? LOG_ERROR : LOG_WARN, probs.get(i).oneLine());
            log(LOG_ERROR, "Script not started: fix the error" + (probs.size() > 1 ? "s" : "") + " above.");
            status = ERROR; error = probs.get(0);
            link.onStatus(status, error);
            return probs;
        }
        for (int i = 0; i < probs.size(); i++) log(LOG_WARN, probs.get(i).oneLine());
        interp = new ScriptInterp(this);
        interp.loader = loader;
        interp.store.putAll(memory);
        ScriptApi.install(interp, this);
        try { program = ScriptParser.parse(source, "main"); }
        catch (ScriptError e) { fail(e); return probs; }
        ThreadCtx main = newCtx("main", 0);
        main.entry = null;
        threads.add(main);
        status = RUNNING;
        log(LOG_INFO, "running " + name);
        link.onStatus(status, null);
        return probs;
    }

    ThreadCtx newCtx(String name, int prio) {
        ThreadCtx c = new ThreadCtx(nextId++, name);
        c.priority = prio;
        c.thread = new Thread(null, c, "pp-script-" + c.id, 2L * 1024 * 1024);
        c.thread.setDaemon(true);
        return c;
    }

    ThreadCtx spawn(Object fn, Object[] args, String name, int prio) {
        ThreadCtx c = newCtx(name, prio);
        c.entry = fn; c.entryArgs = args;
        threads.add(c);
        return c;
    }

    boolean isRunning() { return status == RUNNING; }

    // ------------------------------------------------------------ scheduling
    /** Script thread -> game: wait until the scheduler resumes this thread. */
    void handBack(ThreadCtx c) {
        sched.release();
        c.wake.acquireUninterruptibly();
        if (c.killed) throw new ScriptError.Kill();
    }

    /** Game -> script thread: run it until it hands control back. */
    private void resume(ThreadCtx c) {
        if (c.finished) return;
        if (!c.started) { c.started = true; c.thread.start(); }
        c.ops = 0;
        c.wake.release();
        sched.acquireUninterruptibly();
    }

    /** Game thread, once per frame (before the pilot runs). */
    void update(float dt) {
        if (status != RUNNING) return;
        runTime += dt;
        for (int i = 0; i < threads.size(); i++) {
            ThreadCtx c = threads.get(i);
            if (c.finished) continue;
            if (c.sleeping) { c.sleepLeft -= dt; if (c.sleepLeft > 0) continue; c.sleeping = false; }
            if (c.waiting != null && !c.waiting.done) continue;
            if (c.joining != null && !c.joining.finished) continue;
            c.joining = null;
            resume(c);
            if (c.finished && c.error != null && status == RUNNING) { fail(c.error); return; }
            if (stopRequested) { stop(true); return; }
        }
        for (int i = threads.size() - 1; i >= 0; i--) if (threads.get(i).finished) { pilot.cancelOwner(threads.get(i)); threads.remove(i); }
        if (threads.isEmpty() && status == RUNNING) {
            interp.flushPrint();
            memory.putAll(interp.store);
            status = FINISHED;
            log(LOG_INFO, "script finished");
            link.onStatus(status, null);
        }
    }

    private void killAll() {
        for (int i = 0; i < threads.size(); i++) {
            ThreadCtx c = threads.get(i);
            c.killed = true;
            if (c.started && !c.finished) { resume(c); }
            else if (!c.started) c.finished = true;
        }
        threads.clear();
        pilot.cancelAll();
    }

    private void fail(ScriptError e) {
        if (interp != null) interp.flushPrint();
        killAll();
        if (interp != null) memory.putAll(interp.store);
        status = ERROR; error = e;
        log(LOG_ERROR, e.report());
        link.onStatus(status, e);
    }

    /** Stop the script (user pressed STOP, run ended, or the script called stop()). */
    void stop(boolean announce) {
        if (status == RUNNING) {
            if (interp != null) { interp.flushPrint(); memory.putAll(interp.store); }
            killAll();
            status = STOPPED;
            if (announce) { log(LOG_INFO, "script stopped"); link.onStatus(status, null); }
        } else { killAll(); }
        pilot.reset();
    }

    /** Called by the stop() script function. */
    void requestStop() { stopRequested = true; }

    // ------------------------------------------------------------ action results
    /** An action did not work. Scripts get a warning (or an ActionError in strict mode). */
    void failed(ScriptValues.Frame f, String fname, String reason) {
        lastFail = fname + "(): " + reason;
        if (interp != null && interp.strict) throw new ScriptError("ActionError", fname + "(): " + reason, f == null ? 0 : f.line, 0).hint("set_strict(False) turns this error into a warning");
        warnOnce(fname + reason, "line " + (f == null ? 0 : f.line) + ": " + fname + "(): " + reason);
    }
}
