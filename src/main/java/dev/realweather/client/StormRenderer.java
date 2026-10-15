package dev.realweather.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.realweather.RealWeather;
import dev.realweather.config.ClientConfig;
import dev.realweather.config.Quality;
import dev.realweather.storm.StormState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Два полноэкранных прохода:
 *  1) AFTER_SKY — процедурное небо (градиент, закат, солнце, луна, звёзды, фоновые облака);
 *  2) AFTER_TRANSLUCENT_BLOCKS — объёмные облака штормов (ray marching) с учётом буфера глубины.
 * Все вычисления облаков выполняются в GLSL; Java только заполняет uniform'ы.
 */
public final class StormRenderer {
    private static ShaderInstance skyShader;
    private static ShaderInstance cloudShader;
    private static final int MAX_STORMS = 4;
    private static final float[] SD = new float[MAX_STORMS * 16];

    private StormRenderer() {}

    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(RealWeather.MODID, "sky"), DefaultVertexFormat.POSITION),
                    s -> skyShader = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(RealWeather.MODID, "storm_clouds"), DefaultVertexFormat.POSITION),
                    s -> cloudShader = s);
        } catch (IOException ex) {
            throw new RuntimeException("RealWeather: не удалось загрузить шейдеры", ex);
        }
    }

    public static void onRenderStage(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != Level.OVERWORLD || !ClientConfig.ENABLED.get()) return;
        RenderLevelStageEvent.Stage stage = e.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            if (ClientConfig.CUSTOM_SKY.get() && skyShader != null) drawSky(e, level);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (ClientConfig.STORM_CLOUDS.get() && cloudShader != null && ClientStorms.active()) drawStorms(e, level);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void set1(ShaderInstance s, String n, float v) {
        Uniform u = s.getUniform(n);
        if (u != null) u.set(v);
    }

    private static void set2(ShaderInstance s, String n, float a, float b) {
        Uniform u = s.getUniform(n);
        if (u != null) u.set(a, b);
    }

    private static void set3(ShaderInstance s, String n, float a, float b, float c) {
        Uniform u = s.getUniform(n);
        if (u != null) u.set(a, b, c);
    }

    private static void set3(ShaderInstance s, String n, float[] v) {
        set3(s, n, v[0], v[1], v[2]);
    }

    private static void fullscreenQuad() {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1f, -1f, 0f);
        b.addVertex(1f, -1f, 0f);
        b.addVertex(1f, 1f, 0f);
        b.addVertex(-1f, 1f, 0f);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    private static Matrix4f invViewProj(RenderLevelStageEvent e) {
        return new Matrix4f(e.getProjectionMatrix()).mul(e.getModelViewMatrix()).invert();
    }

    private static void beginPass() {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    private static void endPass() {
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // ---------------------------------------------------------------- sky

    private static void drawSky(RenderLevelStageEvent e, ClientLevel level) {
        Camera cam = e.getCamera();
        Vec3 cp = cam.getPosition();
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now = level.getGameTime() + pt;
        SkyModel m = SkyModel.INSTANCE;
        m.update(level, pt, cp.x, cp.z, now);
        Quality q = ClientConfig.QUALITY.get();
        ShaderInstance s = skyShader;

        Uniform inv = s.getUniform("InvViewProj");
        if (inv != null) inv.set(invViewProj(e));
        set3(s, "CamPos", (float) cp.x, (float) cp.y, (float) cp.z);
        set1(s, "Time", (float) (now / 20.0));
        set3(s, "SunDir", m.sunDir);
        set3(s, "SunColor", m.sunColor);
        set3(s, "ZenithColor", m.zenith);
        set3(s, "HorizonColor", m.horizon);
        set3(s, "GlowColor", m.glow);
        set3(s, "AmbientColor", m.ambient);
        set1(s, "Night", m.night);
        set1(s, "Darkness", m.darkness);
        float rain = level.getRainLevel(pt);
        set1(s, "Cover", 0.40f + 0.35f * Math.max(rain, m.darkness));
        set1(s, "CloudHeight", 230f);
        set2(s, "WindOffset", (float) (now * 0.05), (float) (now * 0.02));
        set1(s, "FlashAmt", ClientStorms.flashAmount(now));
        set1(s, "CloudOct", q.skyOct);
        set1(s, "AmbientCloud", ClientConfig.AMBIENT_CLOUDS.get() ? 1f : 0f);

        beginPass();
        RenderSystem.setShader(() -> s);
        fullscreenQuad();
        endPass();
    }

    // ---------------------------------------------------------------- storms

    private static void drawStorms(RenderLevelStageEvent e, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = e.getCamera();
        Vec3 cp = cam.getPosition();
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now = level.getGameTime() + pt;
        Quality q = ClientConfig.QUALITY.get();
        float maxDist = (float) Math.min(q.maxDist, ClientConfig.VIEW_DISTANCE.get());

        List<ClientStorms.Entry> sorted = new ArrayList<>(ClientStorms.entries());
        sorted.sort(Comparator.comparingDouble(en -> Math.hypot(en.x(now) - cp.x, en.z(now) - cp.z)));
        int n = 0;
        java.util.Arrays.fill(SD, 0f);
        for (ClientStorms.Entry en : sorted) {
            if (n >= MAX_STORMS) break;
            StormState st = en.st;
            double dist = Math.hypot(en.x(now) - cp.x, en.z(now) - cp.z);
            if (dist - st.radius * 7.0 > maxDist) continue;       // слишком далеко, даже наковальня не видна
            int o = n * 16;
            SD[o] = (float) (en.x(now) - cp.x);
            SD[o + 1] = (float) (en.z(now) - cp.z);
            SD[o + 2] = st.baseY;
            SD[o + 3] = st.topY;
            SD[o + 4] = st.intensity;
            SD[o + 5] = st.growth;
            SD[o + 6] = st.dirX;
            SD[o + 7] = st.dirZ;
            SD[o + 8] = st.tornado;
            SD[o + 9] = st.groundY;
            SD[o + 10] = st.radius;
            SD[o + 11] = (float) ((now / 20.0) * 0.22 + st.id * 1.3);
            SD[o + 12] = (float) (en.tx(now) - cp.x);
            SD[o + 13] = (float) (en.tz(now) - cp.z);
            SD[o + 14] = st.rain;
            SD[o + 15] = st.shape;
            n++;
        }
        if (n == 0) return;

        SkyModel m = SkyModel.INSTANCE;
        m.update(level, pt, cp.x, cp.z, now);
        ShaderInstance s = cloudShader;
        Uniform inv = s.getUniform("InvViewProj");
        if (inv != null) inv.set(invViewProj(e));
        set3(s, "CamPos", (float) cp.x, (float) cp.y, (float) cp.z);
        set1(s, "Time", (float) (now / 20.0));
        set3(s, "SunDir", m.sunDir);
        set3(s, "SunColor", m.sunColor);
        set3(s, "AmbientColor", m.ambient);
        set3(s, "HazeColor", m.horizon);
        set1(s, "Steps", q.steps);
        set1(s, "MaxDist", maxDist);
        set1(s, "DetailOct", q.oct);
        set1(s, "LightSteps", q.lightSteps);
        set1(s, "Density", ClientConfig.CLOUD_DENSITY.get().floatValue());
        set1(s, "Contrast", ClientConfig.CONTRAST.get().floatValue());
        set1(s, "AnvilScale", ClientStorms.params.anvil());
        float flash = ClientStorms.flashAmount(now);
        set1(s, "FlashAmt", flash);
        set3(s, "FlashPos", (float) (ClientStorms.flashX() - cp.x), (float) ClientStorms.flashY(),
                (float) (ClientStorms.flashZ() - cp.z));
        set1(s, "StormCount", n);
        set1(s, "Darkness", m.darkness);
        Uniform sd = s.getUniform("SD");
        if (sd != null) sd.set(SD);
        s.setSampler("DepthSampler", mc.getMainRenderTarget().getDepthTextureId());

        beginPass();
        RenderSystem.setShader(() -> s);
        fullscreenQuad();
        endPass();
    }
}
