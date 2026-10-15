package dev.realweather.config;

/** Профили качества: меняют только стоимость визуализации, но не физику. */
public enum Quality {
    LOW(28, 1800f, 2, 2, 2, 3),
    MEDIUM(44, 2800f, 3, 3, 4, 4),
    HIGH(64, 4000f, 4, 3, 8, 5),
    ULTRA(96, 6000f, 5, 4, 14, 5);

    public final int steps;
    public final float maxDist;
    public final int oct;
    public final int lightSteps;
    public final int particles;
    public final int skyOct;

    Quality(int steps, float maxDist, int oct, int lightSteps, int particles, int skyOct) {
        this.steps = steps;
        this.maxDist = maxDist;
        this.oct = oct;
        this.lightSteps = lightSteps;
        this.particles = particles;
        this.skyOct = skyOct;
    }
}
