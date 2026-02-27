package org.planet.core.generation;

public class ConsoleStageListener implements StageListener {

    @Override
    public void onStageStart(StageId id, String name) {
        System.out.println("[STAGE START] " + id + " - " + name);
    }

    @Override
    public void onStageEnd(StageId id, String name, long elapsedMs) {
        System.out.println("[STAGE END]   " + id + " - " + name + " (" + elapsedMs + " ms)");
    }
}
