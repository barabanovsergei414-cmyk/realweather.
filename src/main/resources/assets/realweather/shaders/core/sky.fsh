#version 150

uniform mat4 InvViewProj;
uniform vec3 CamPos;
uniform float Time;
uniform vec3 SunDir;
uniform vec3 SunColor;
uniform vec3 ZenithColor;
uniform vec3 HorizonColor;
uniform vec3 GlowColor;
uniform vec3 AmbientColor;
uniform float Night;
uniform float Darkness;
uniform float Cover;
uniform float CloudHeight;
uniform vec2 WindOffset;
uniform float FlashAmt;
uniform float CloudOct;
uniform float AmbientCloud;

in vec2 ndc;
out vec4 fragColor;

float ss(float a, float b, float x) { return clamp((x - a) / (b - a), 0.0, 1.0); }

float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x),
                   mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x),
                   mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p, int oct) {
    float a = 0.5;
    float s = 0.0;
    for (int i = 0; i < oct; i++) {
        s += a * vnoise(p);
        p = p * 2.02 + vec3(17.1, 3.7, 9.2);
        a *= 0.5;
    }
    return s;
}

vec3 tm(vec3 c) { return 1.0 - exp(-c * 1.2); }

void main() {
    vec4 far = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 v = normalize(far.xyz / far.w);
    float y = v.y;

    // --- градиент неба: плавный переход зенит -> горизонт, без жёстких палитр
    float t = pow(clamp(y, 0.0, 1.0), 0.42);
    vec3 col = mix(HorizonColor, ZenithColor, t);
    col = mix(col, HorizonColor * 0.85, ss(0.0, -0.25, y));

    // --- рассеяние вокруг солнца и сумеречная полоса в направлении азимута солнца
    float sd = dot(v, SunDir);
    col += SunColor * (pow(max(sd, 0.0), 12.0) * 0.18 + pow(max(sd, 0.0), 220.0) * 0.9);
    vec2 vf = normalize(v.xz + vec2(1e-5));
    vec2 sf = normalize(SunDir.xz + vec2(1e-5));
    float az = max(dot(vf, sf), 0.0);
    col += GlowColor * pow(az, 3.0) * exp(-abs(y) * 5.0) * 0.8;
    col += GlowColor * 0.15 * exp(-abs(y) * 6.0);

    // --- диск солнца (с учётом горизонта)
    col += SunColor * 8.0 * ss(0.99905, 0.99935, sd) * step(-0.02, y);

    // --- луна и звёзды (только ночью)
    vec3 md = -SunDir;
    float mc = dot(v, md);
    float moon = ss(0.99880, 0.99900, mc) * step(0.0, y);
    float maria = vnoise(v * 38.0);
    col += vec3(0.85, 0.90, 1.0) * moon * (0.65 + 0.35 * maria) * Night * (1.0 - Darkness);
    col += vec3(0.25, 0.30, 0.45) * pow(max(mc, 0.0), 40.0) * 0.12 * Night * step(0.0, y);

    vec3 sp = v * 220.0;
    vec3 cell = floor(sp);
    float hs = hash13(cell);
    vec3 fr = fract(sp) - 0.5;
    float star = ss(0.9965, 1.0, hs) * ss(0.35, 0.0, length(fr));
    float tw = 0.75 + 0.25 * sin(Time * 3.0 + hs * 60.0);
    col += vec3(0.9, 0.95, 1.0) * star * tw * Night * ss(0.0, 0.2, y) * (1.0 - Darkness) * (1.0 - moon);

    // --- фоновые облака (дешёвый 2D-слой: кучевые + перистые), закреплены в мировых координатах
    if (AmbientCloud > 0.5 && y > 0.015) {
        int oc = int(CloudOct);
        if (CamPos.y < CloudHeight) {
            float tp = (CloudHeight - CamPos.y) / y;
            vec2 xz = CamPos.xz + v.xz * tp + WindOffset;
            float sc = 0.0032;
            float n = fbm(vec3(xz * sc, Time * 0.006), oc);
            float dd = ss(1.0 - Cover - 0.05, 1.0 - Cover + 0.20, n);
            float n2 = fbm(vec3((xz + SunDir.xz * 90.0) * sc, Time * 0.006), oc);
            float lit = clamp(0.62 - (n2 - n) * 3.0, 0.0, 1.0);
            float silver = pow(max(sd, 0.0), 8.0) * (1.0 - dd * 0.5);
            vec3 cc = SunColor * (0.35 + 0.85 * lit) * (1.0 + 0.6 * silver)
                    + AmbientColor * (0.7 + 0.3 * lit);
            cc = mix(cc, vec3(dot(cc, vec3(0.333))) * vec3(0.62, 0.65, 0.68), Darkness * 0.8);
            float fade = ss(0.015, 0.20, y) * (1.0 - ss(3500.0, 9000.0, tp));
            col = mix(col, cc, dd * fade * 0.95);
        }
        if (CamPos.y < 700.0) {
            float tc = (700.0 - CamPos.y) / y;
            vec2 xc = CamPos.xz + v.xz * tc + WindOffset * 0.6;
            float nc = fbm(vec3(xc.x * 0.0007, xc.y * 0.0030, Time * 0.003), max(oc - 1, 2));
            float al = ss(0.52, 0.85, nc) * 0.45 * ss(0.02, 0.25, y) * (1.0 - Darkness);
            vec3 cw = SunColor * 0.9 + AmbientColor * 0.6;
            col = mix(col, cw, al);
        }
    }

    // --- отблеск вспышек молний на небе
    col += vec3(0.50, 0.60, 0.85) * FlashAmt * 0.35 * ss(-0.1, 0.6, y);

    fragColor = vec4(tm(col), 1.0);
}
