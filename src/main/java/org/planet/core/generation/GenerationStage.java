package org.planet.core.generation;

public interface GenerationStage {
    StageId id();
    String name();
    void apply(WorldContext ctx);
}
