package dev.codex.watchworkshop;

import java.util.concurrent.Executor;

/** One in-flight write and one replaceable pending selection; no retry loop. */
final class LatestThemeQueue<T> {
    interface Worker<T> {
        void apply(T value) throws Exception;
        void failed(Exception error);
        void idle();
    }
    private final Executor executor;
    private final Worker<T> worker;
    private T pending;
    private boolean running, recovering;

    LatestThemeQueue(Executor executor, Worker<T> worker) {
        this.executor=executor; this.worker=worker;
    }
    synchronized boolean submit(T value) {
        if(recovering)return false;
        pending=value;
        if(!running){running=true;executor.execute(this::drain);}
        return true;
    }
    synchronized boolean isRunning(){return running;}
    private void drain() {
        for(;;){
            T value;
            synchronized(this){
                value=pending;pending=null;
                if(value==null){running=false;break;}
            }
            try{worker.apply(value);}
            catch(Exception error){
                synchronized(this){pending=null;recovering=true;}
                try{worker.failed(error);}
                finally{synchronized(this){pending=null;recovering=false;running=false;}worker.idle();}
                return;
            }
        }
        worker.idle();
    }
}
