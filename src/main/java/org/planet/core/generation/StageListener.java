package org.planet.core.generation;

public interface StageListener {
    void onStageStart(StageId id, String name);
    void onStageEnd(StageId id, String name, long elapsedMs);
}
