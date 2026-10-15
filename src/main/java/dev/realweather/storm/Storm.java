package dev.realweather.storm;

import dev.realweather.MathUtil;
import dev.realweather.config.RWConfig;
import dev.realweather.network.FlashPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Одна грозовая ячейка: единый центр, направление, время жизни и стадия развития.
 * Модель игровая (упрощённая): неустойчивость берётся из температуры биома и времени суток.
 */
public final class Storm {
    public final StormState st = new StormState();
    public final RandomSource rnd = RandomSource.create();
    public long age;
    public long life;
    public float goal;
    public float instability = 0.85f;
    public float potential = 1f;
    public boolean tornadic;
    public float heading;
    public boolean dead;
    private float shapeTarget = 0.3f;

    public Storm(int id, double x, double z, float heading, float intensity, boolean tornadic, double startFraction) {
        st.id = id;
        st.x = x;
        st.z = z;
        this.heading = heading;
        this.goal = Mth.clamp(intensity, 0.1f, 1f);
        this.tornadic = tornadic;
        this.potential = Mth.clamp(RWConfig.TORNADO_INTENSITY.get().floatValue() * (0.6f + 0.4f * intensity), 0.1f, 1f);
        st.radius = (float) (120.0 * RWConfig.RADIUS_SCALE.get() * (0.85 + 0.3 * rnd.nextFloat()));
        st.baseY = 140f + rnd.nextFloat() * 25f;
        st.topY = st.baseY + (float) ((230.0 + 60.0 * rnd.nextFloat()) * RWConfig.TOWER_SCALE.get());
        st.speed = (float) ((0.14 + 0.08 * rnd.nextFloat()) * RWConfig.SPEED_SCALE.get());
        life = (long) (RWConfig.LIFETIME_MINUTES.get() * 1200.0 * (0.8 + 0.4 * rnd.nextFloat()));
        age = (long) (life * startFraction);
        st.dirX = Mth.cos(heading);
        st.dirZ = Mth.sin(heading);
        st.shape = 0.3f;
        // чтобы при старте с середины жизни шторм сразу был развит
        float t = (float) age / life;
        st.growth = MathUtil.smooth(0f, 0.28f, t);
        st.intensity = goal * 0.9f * MathUtil.smooth(0f, 0.28f, t);
        if (tornadic && t > 0.4f) st.tornado = 0.5f * potential;
        st.rain = (float) (RWConfig.RAIN_INTENSITY.get() * MathUtil.smooth(0.15f, 0.5f, st.growth));
    }

    public void tick(ServerLevel level) {
        age++;
        float t = (float) age / (float) life;
        float grow = MathUtil.smooth(0f, 0.28f, t);
        float decay = 1f - MathUtil.smooth(0.72f, 1f, t);
        st.growth = grow * (0.4f + 0.6f * decay);

        if (age % 40 == 0) sampleEnvironment(level);

        float target = goal * instability * (float) Math.pow(decay, 0.7) * grow;
        st.intensity += (target - st.intensity) * 0.03f;

        heading += (rnd.nextFloat() - 0.5f) * 0.004f;
        st.dirX = Mth.cos(heading);
        st.dirZ = Mth.sin(heading);
        st.x += st.dirX * st.speed;
        st.z += st.dirZ * st.speed;

        float rainTarget = (float) (RWConfig.RAIN_INTENSITY.get() * MathUtil.smooth(0.15f, 0.5f, st.growth));
        st.rain += (rainTarget - st.rain) * 0.02f;

        float tTarget = 0f;
        if (tornadic) {
            float form = MathUtil.smooth(0.34f, 0.44f, t);
            float rope = 1f - MathUtil.smooth(0.62f, 0.80f, t);
            tTarget = form * rope * potential * st.intensity;
        }
        st.tornado += (tTarget - st.tornado) * 0.012f;
        st.tornado = Mth.clamp(st.tornado, 0f, 1f);

        float wedge = MathUtil.smooth(0.34f, 0.55f, t) * (1f - MathUtil.smooth(0.55f, 0.78f, t));
        shapeTarget = 0.25f + 0.75f * wedge * (0.6f + 0.4f * (float) Math.sin(age * 0.0007 + st.id));
        st.shape += (shapeTarget - st.shape) * 0.01f;

        float r = st.radius;
        float wob = Mth.sin(age * 0.011f + st.id) * r * 0.03f;
        float px = -st.dirZ, pz = st.dirX; // перпендикуляр к движению
        st.tOffX = -st.dirX * r * 0.10f - px * r * 0.10f + px * wob;
        st.tOffZ = -st.dirZ * r * 0.10f - pz * r * 0.10f + pz * wob;

        if (age % 10 == 0) updateGround(level);
        tickLightning(level);
        if (age >= life) dead = true;
    }

    private void sampleEnvironment(ServerLevel level) {
        Biome biome = level.getBiome(BlockPos.containing(st.x, level.getSeaLevel(), st.z)).value();
        float temp = biome.getBaseTemperature();
        float f = Mth.clamp(0.35f + 0.65f * Mth.clamp(temp / 1.0f, 0f, 1f), 0.25f, 1f);
        if (!biome.hasPrecipitation()) f *= 0.65f;
        float tod = level.getTimeOfDay(1f);
        float heating = 0.8f + 0.2f * Mth.cos(tod * (float) (Math.PI * 2));
        instability += (Mth.clamp(f * heating, 0.2f, 1f) - instability) * 0.5f;
    }

    private void updateGround(ServerLevel level) {
        int bx = Mth.floor(st.tornadoX()), bz = Mth.floor(st.tornadoZ());
        if (level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            st.groundY += (h - st.groundY) * 0.25f;
        } else {
            st.groundY += (level.getSeaLevel() - st.groundY) * 0.02f;
        }
    }

    private void tickLightning(ServerLevel level) {
        float rate = RWConfig.LIGHTNING_RATE.get().floatValue();
        float p = rate / 1200f * st.intensity * st.intensity * MathUtil.smooth(0.3f, 0.6f, st.growth);
        if (rnd.nextFloat() >= p) return;
        float r = st.radius;
        boolean ground = RWConfig.GROUND_STRIKES.get() && rnd.nextFloat() > 0.7f;
        if (!ground) {
            double x = st.x + (rnd.nextFloat() - 0.5f) * r * 1.2f;
            double z = st.z + (rnd.nextFloat() - 0.5f) * r * 1.2f;
            double y = st.baseY + (st.topY - st.baseY) * (0.25 + 0.6 * rnd.nextFloat()) * Math.max(st.growth, 0.1f);
            PacketDistributor.sendToPlayersInDimension(level, new FlashPayload((float) x, (float) y, (float) z, true));
        } else {
            double cx = st.x + st.dirX * r * 0.45, cz = st.z + st.dirZ * r * 0.45;
            double ang = rnd.nextDouble() * Math.PI * 2, rad = Math.sqrt(rnd.nextDouble()) * r * 0.5;
            double x = cx + Math.cos(ang) * rad, z = cz + Math.sin(ang) * rad;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) return;
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                bolt.moveTo(x, y, z);
                bolt.setVisualOnly(!RWConfig.LIGHTNING_DAMAGE.get());
                level.addFreshEntity(bolt);
            }
            PacketDistributor.sendToPlayersInDimension(level, new FlashPayload((float) x, (float) (y + 90), (float) z, false));
        }
    }
}
