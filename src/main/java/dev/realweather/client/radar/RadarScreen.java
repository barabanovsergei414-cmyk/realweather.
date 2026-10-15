package dev.realweather.client.radar;

import dev.realweather.client.ClientStorms;
import dev.realweather.storm.StormState;
import dev.realweather.tornado.TornadoParams;
import dev.realweather.tornado.WindField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Погодный радар. Данные берутся из ClientStorms (состояние погодного движка), а радиальная
 * скорость считается из того же поля ветра, что и физика (WindField), а не рисуется произвольно.
 * Радар находится в позиции игрока; север сверху.
 */
public class RadarScreen extends Screen {
    private static final int GRID = 72;
    private static final int[] RANGES = {500, 1000, 2000, 4000, 8000};
    private static final int[] DBZ_COLORS = {
            0xFF04E9E7, 0xFF019FF4, 0xFF0300F4, 0xFF02FD02, 0xFF01C501, 0xFF008E00, 0xFFFDF802,
            0xFFE5BC00, 0xFFFD9500, 0xFFFD0000, 0xFFD40000, 0xFFBC0000, 0xFFF800FD, 0xFF9854C6};

    private int mode = 0;          // 0 — отражаемость, 1 — радиальная скорость
    private int rangeIdx = 2;
    private int frameOffset = 0;   // 0 — онлайн, N — N-й кадр назад
    private int cell;
    private int mapX, mapY, mapPx;

    public RadarScreen() {
        super(Component.translatable("screen.realweather.radar"));
    }

    @Override
    protected void init() {
        cell = Math.max(2, (Math.min(width, height) - 50) / GRID);
        mapPx = cell * GRID;
        mapX = (width - mapPx) / 2 - 70;
        mapY = (height - mapPx) / 2;
        int bx = mapX + mapPx + 10, by = mapY;
        addRenderableWidget(Button.builder(Component.translatable("radar.mode.refl"), b -> {
            mode ^= 1;
            b.setMessage(Component.translatable(mode == 0 ? "radar.mode.refl" : "radar.mode.vel"));
        }).bounds(bx, by, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar.zoom_in"),
                b -> rangeIdx = Math.max(0, rangeIdx - 1)).bounds(bx, by + 24, 63, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar.zoom_out"),
                b -> rangeIdx = Math.min(RANGES.length - 1, rangeIdx + 1)).bounds(bx + 67, by + 24, 63, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar.older"),
                b -> frameOffset = Math.min(ClientStorms.history().size(), frameOffset + 1)).bounds(bx, by + 48, 63, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar.newer"),
                b -> frameOffset = Math.max(0, frameOffset - 1)).bounds(bx + 67, by + 48, 63, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar.live"),
                b -> frameOffset = 0).bounds(bx, by + 72, 130, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        rangeIdx = Mth.clamp(rangeIdx + (sy > 0 ? -1 : 1), 0, RANGES.length - 1);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private List<StormState> source(double now) {
        if (frameOffset == 0) return ClientStorms.live(now).storms;
        List<ClientStorms.Snapshot> h = ClientStorms.history();
        if (h.isEmpty()) return List.of();
        int idx = Mth.clamp(h.size() - frameOffset, 0, h.size() - 1);
        return h.get(idx).storms;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        double now = mc.level.getGameTime() + pt;
        g.fill(0, 0, width, height, 0xE0050A10);
        g.fill(mapX - 2, mapY - 2, mapX + mapPx + 2, mapY + mapPx + 2, 0xFF1E2A33);
        g.fill(mapX, mapY, mapX + mapPx, mapY + mapPx, 0xFF08110C);

        List<StormState> storms = source(now);
        double range = RANGES[rangeIdx];
        double px = mc.player.getX(), pz = mc.player.getZ();
        TornadoParams prm = ClientStorms.params;
        double[] w = new double[3];

        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                double wx = px + ((gx + 0.5) / GRID - 0.5) * 2 * range;
                double wz = pz + ((gy + 0.5) / GRID - 0.5) * 2 * range;
                float dbz = reflectivity(storms, wx, wz);
                if (dbz < 8f) continue;
                int col;
                if (mode == 0) {
                    col = DBZ_COLORS[Mth.clamp((int) ((dbz - 8f) / 4.5f), 0, DBZ_COLORS.length - 1)];
                } else {
                    col = velocityColor(radialVelocity(storms, wx, wz, px, pz, prm, w));
                }
                int x0 = mapX + gx * cell, y0 = mapY + gy * cell;
                g.fill(x0, y0, x0 + cell, y0 + cell, col);
            }
        }

        // кольца дальности и перекрестие
        int cx = mapX + mapPx / 2, cy = mapY + mapPx / 2;
        for (int k = 1; k <= 3; k++) ring(g, cx, cy, mapPx / 2.0 * k / 3.0, 0x40FFFFFF);
        g.fill(cx - 4, cy, cx + 5, cy + 1, 0xFFFFFFFF);
        g.fill(cx, cy - 4, cx + 1, cy + 5, 0xFFFFFFFF);

        // маркеры штормов, направление движения, торнадо
        StormState nearest = null;
        double nd = Double.MAX_VALUE;
        for (StormState s : storms) {
            double dx = s.x - px, dz = s.z - pz;
            double d = Math.hypot(dx, dz);
            if (d < nd) { nd = d; nearest = s; }
            int sx = cx + (int) (dx / range * mapPx / 2.0), sy = cy + (int) (dz / range * mapPx / 2.0);
            if (inMap(sx, sy)) {
                g.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFFFFFFFF);
                g.drawString(font, s.growth > 0.5f && s.intensity > 0.6f ? "SC" : "TS", sx + 5, sy - 4, 0xFFFFFF, false);
                int ex = sx + (int) (s.dirX * 22), ey = sy + (int) (s.dirZ * 22);
                line(g, sx, sy, ex, ey, 0xFFFFFFFF);
            }
            if (s.tornado > 0.12f) {
                int tx = cx + (int) ((s.tornadoX() - px) / range * mapPx / 2.0);
                int ty = cy + (int) ((s.tornadoZ() - pz) / range * mapPx / 2.0);
                if (inMap(tx, ty)) {
                    g.fill(tx - 3, ty - 3, tx + 4, ty + 4, 0xFFFF2020);
                    g.drawString(font, "T", tx + 6, ty - 4, 0xFF5050, false);
                }
            }
        }
        // след движения по истории
        List<ClientStorms.Snapshot> hist = ClientStorms.history();
        for (int i = 0; i < hist.size(); i++) {
            int a = 0x30 + 0x60 * i / Math.max(1, hist.size());
            for (StormState s : hist.get(i).storms) {
                int sx = cx + (int) ((s.x - px) / range * mapPx / 2.0), sy = cy + (int) ((s.z - pz) / range * mapPx / 2.0);
                if (inMap(sx, sy)) g.fill(sx, sy, sx + 2, sy + 2, (a << 24) | 0xFFFFFF);
            }
        }

        drawLegend(g);
        drawInfo(g, mc, nearest, nd, storms.size(), range, now);
        super.render(g, mx, my, pt);
    }

    private boolean inMap(int x, int y) {
        return x >= mapX && y >= mapY && x < mapX + mapPx && y < mapY + mapPx;
    }

    private void drawLegend(GuiGraphics g) {
        int lx = mapX + mapPx + 10, ly = mapY + 104;
        g.drawString(font, mode == 0 ? "dBZ" : "m/s", lx, ly, 0xFFFFFF, false);
        if (mode == 0) {
            for (int i = 0; i < DBZ_COLORS.length; i++) {
                g.fill(lx, ly + 12 + i * 9, lx + 14, ly + 20 + i * 9, DBZ_COLORS[i]);
                g.drawString(font, String.valueOf(8 + (int) (i * 4.5f)), lx + 20, ly + 12 + i * 9, 0xCCCCCC, false);
            }
        } else {
            float[] marks = {-30, -20, -10, 0, 10, 20, 30};
            for (int i = 0; i < marks.length; i++) {
                g.fill(lx, ly + 12 + i * 11, lx + 14, ly + 21 + i * 11, velocityColor(marks[i]));
                g.drawString(font, (marks[i] > 0 ? "+" : "") + (int) marks[i], lx + 20, ly + 12 + i * 11, 0xCCCCCC, false);
            }
            g.drawString(font, "<- toward / away ->", lx, ly + 12 + marks.length * 11 + 2, 0x999999, false);
        }
    }

    private void drawInfo(GuiGraphics g, Minecraft mc, StormState nearest, double nd, int count, double range, double now) {
        long dt = mc.level.getDayTime();
        int hours = (int) (((dt / 1000) + 6) % 24);
        int mins = (int) ((dt % 1000) * 60 / 1000);
        String time = String.format("День %d, %02d:%02d", dt / 24000 + 1, hours, mins);
        String view = frameOffset == 0 ? "LIVE" : "T-" + (frameOffset * 5) + " s";
        int x = mapX, y = mapY - 12;
        g.drawString(font, time + "   |   " + view + "   |   " + (int) range + " блоков (радиус)   |   ячеек: " + count,
                x, y, 0xFFFFFF, false);
        int ly = mapY + mapPx + 6;
        if (nearest != null) {
            g.drawString(font, String.format("Ближайшая ячейка #%d: %.0f м, интенсивность %.2f, торнадо %.2f, скорость %.1f бл/с",
                    nearest.id, nd, nearest.intensity, nearest.tornado, nearest.speed * 20f), x, ly, 0xCCCCCC, false);
        } else {
            g.drawString(font, "Штормов нет. Создайте командой /realweather tornado", x, ly, 0xCCCCCC, false);
        }
        g.drawString(font, "Игровая упрощённая модель погоды (не CM1). Колесо мыши — масштаб.", x, ly + 11, 0x777777, false);
    }

    // ------------------------------------------------------------ модель радара

    /** Отражаемость (dBZ) из состояния ячеек: дождевое ядро, hook-эхо у торнадо, слабое эхо наковальни. */
    static float reflectivity(List<StormState> storms, double x, double z) {
        float best = 0f;
        for (StormState s : storms) {
            double R = s.radius;
            double cx = s.x + s.dirX * R * 0.45, cz = s.z + s.dirZ * R * 0.45;
            double d = Math.hypot(x - cx, z - cz) / (R * 0.55);
            float dbz = (float) ((10.0 + 50.0 * s.intensity * Math.max(s.rain, 0.2f)) * Math.exp(-d * d * 1.2));
            if (s.tornado > 0.08f) {
                double hx = s.tornadoX() - s.dirX * R * 0.05, hz = s.tornadoZ() - s.dirZ * R * 0.05;
                double dh = Math.hypot(x - hx, z - hz) / (R * 0.16);
                dbz = Math.max(dbz, (float) ((30.0 + 30.0 * s.tornado) * Math.exp(-dh * dh)));
                double dd = Math.hypot(x - s.tornadoX(), z - s.tornadoZ()) / (R * 0.04 + 6.0);
                dbz = Math.max(dbz, (float) (68.0 * s.tornado * Math.exp(-dd * dd)));   // сигнатура обломков
            }
            double ax = x - (s.x + s.dirX * R * 1.8), az = z - (s.z + s.dirZ * R * 1.8);
            double along = ax * s.dirX + az * s.dirZ, perp = -ax * s.dirZ + az * s.dirX;
            double da = (along / (R * 2.6)) * (along / (R * 2.6)) + (perp / (R * 1.3)) * (perp / (R * 1.3));
            dbz = Math.max(dbz, (float) ((6.0 + 14.0 * s.intensity * s.growth) * Math.exp(-da)));
            best = Math.max(best, dbz);
        }
        return best;
    }

    /** Радиальная скорость в м/с (+ от радара, − к радару) из поля ветра: движение ячейки + мезоциклон + торнадо. */
    static float radialVelocity(List<StormState> storms, double x, double z, double rx, double rz,
                                TornadoParams prm, double[] w) {
        double rdx = x - rx, rdz = z - rz;
        double rl = Math.max(Math.hypot(rdx, rdz), 1.0);
        double ux = rdx / rl, uz = rdz / rl;
        double vx = 0, vz = 0;
        for (StormState s : storms) {
            double R = s.radius;
            double mask = Math.exp(-Math.pow(Math.hypot(x - (s.x + s.dirX * R * 0.5), z - (s.z + s.dirZ * R * 0.5)) / (R * 1.6), 2.0));
            vx += s.dirX * s.speed * mask;
            vz += s.dirZ * s.speed * mask;
            WindField.mesocyclone(x - s.x, z - s.z, R, s.intensity * s.growth, w);
            vx += w[0]; vz += w[2];
            if (s.tornado > 0.05f) {
                WindField.tornado(x - s.tornadoX(), z - s.tornadoZ(), 5.0, s.tornado, prm.spin(), prm.inflow(), 0.0,
                        prm.influence(), 0.0, Math.max(s.baseY - s.groundY, 20.0), 0.0, w);
                vx += w[0]; vz += w[2];
            }
        }
        return (float) ((vx * ux + vz * uz) * 20.0);
    }

    static int velocityColor(float v) {
        float a = Mth.clamp(Math.abs(v) / 35f, 0f, 1f);
        if (Math.abs(v) < 2f) return 0xFF6A6A6A;
        int c = (int) (70 + 185 * a);
        return v < 0 ? (0xFF000000 | (c / 5 << 16) | (c << 8) | (c / 5)) : (0xFF000000 | (c << 16) | (c / 6 << 8) | (c / 6));
    }

    private static void ring(GuiGraphics g, int cx, int cy, double r, int color) {
        int steps = (int) (r * 2.2);
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2 / steps;
            g.fill(cx + (int) (Math.cos(a) * r), cy + (int) (Math.sin(a) * r),
                    cx + (int) (Math.cos(a) * r) + 1, cy + (int) (Math.sin(a) * r) + 1, color);
        }
    }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = x0 + (n == 0 ? 0 : (x1 - x0) * i / n), y = y0 + (n == 0 ? 0 : (y1 - y0) * i / n);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
