package org.planet.core.generation;

public class ResourcePresence {
    public ResourceType type;
    public ResourceLayer layer;
    public int quality;     // 1..100 (примеси)
    public int saturation;  // 1..100
    public int amount;      // условные запасы 1..100
    public double logTonnes; // log10(тонн), может быть 0 если не задан
    public double tonnes;   // оценка в тоннах (для углеводородов через приближённую конверсию)

    public ResourcePresence(ResourceType type, ResourceLayer layer, int quality, int saturation, int amount) {
        this.type = type;
        this.layer = layer;
        this.quality = quality;
        this.saturation = saturation;
        this.amount = amount;
        this.logTonnes = 0.0;
        this.tonnes = 0.0;
    }
}
