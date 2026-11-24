package app.seb3thehacker.gearslip.car

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush

/** Whether this phone can draw the sky on its graphics chip (Android 13 and newer). */
val shaderSkyAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** How much of each ingredient a scene mixes in, 0 to 1. */
private class SkyMix(val cloud: Float, val rain: Float = 0f, val snow: Float = 0f, val fog: Float = 0f, val storm: Float = 0f)

private fun mixOf(scene: SkyScene): SkyMix = when (scene) {
    SkyScene.CLEAR -> SkyMix(cloud = 0.05f)
    SkyScene.PARTLY_CLOUDY -> SkyMix(cloud = 0.45f)
    SkyScene.OVERCAST -> SkyMix(cloud = 0.9f)
    SkyScene.FOG -> SkyMix(cloud = 0.6f, fog = 0.85f)
    SkyScene.DRIZZLE -> SkyMix(cloud = 0.85f, rain = 0.35f)
    SkyScene.RAIN -> SkyMix(cloud = 0.9f, rain = 0.8f)
    SkyScene.SNOW -> SkyMix(cloud = 0.75f, snow = 0.9f)
    SkyScene.STORM -> SkyMix(cloud = 1f, rain = 1f, storm = 1f)
}

/**
 * The weather drawn by a small program on the phone's graphics chip, one pixel at a time: a sky,
 * soft clouds in two layers from layered noise, a glowing sun or stars, fog banks, rain and snow
 * at three depths, and lightning. Written for Gearslip; no outside code.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun ShaderWeatherScene(scene: SkyScene, isDay: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val shader = remember { RuntimeShader(SKY_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
    }
    val mix = mixOf(scene)
    Canvas(modifier) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        // Wrapped well before float precision would make the motion stutter.
        shader.setFloatUniform("iTime", time.floatValue % 3600f)
        shader.setFloatUniform("cloud", mix.cloud)
        shader.setFloatUniform("rain", mix.rain)
        shader.setFloatUniform("snow", mix.snow)
        shader.setFloatUniform("fog", mix.fog)
        shader.setFloatUniform("storm", mix.storm)
        shader.setFloatUniform("day", if (isDay) 1f else 0f)
        shader.setFloatUniform("dim", if (dark) 0.3f else 0f)
        drawRect(brush)
    }
}

private const val SKY_SHADER = """
uniform float2 iResolution;
uniform float iTime;
uniform float cloud;
uniform float rain;
uniform float snow;
uniform float fog;
uniform float storm;
uniform float day;
uniform float dim;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.02 + float2(17.0, 9.0);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fc) {
    float2 uv = fc / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float2 p = float2(uv.x * aspect, uv.y);

    // Sky: clear blue by day, deep navy by night, greyer under cloud, darkest in a storm.
    float3 top = mix(float3(0.02, 0.04, 0.12), mix(float3(0.16, 0.45, 0.85), float3(0.36, 0.42, 0.50), cloud), day);
    float3 bottom = mix(float3(0.10, 0.14, 0.27), mix(float3(0.62, 0.80, 0.97), float3(0.66, 0.71, 0.77), cloud), day);
    top = mix(top, float3(0.14, 0.16, 0.20), storm * 0.7);
    bottom = mix(bottom, float3(0.26, 0.28, 0.33), storm * 0.7);
    float3 col = mix(top, bottom, uv.y);

    // The sun's glow, top right, by day and fading behind cloud.
    float sd = length(p - float2(0.80 * aspect, 0.18));
    col += day * (1.0 - cloud * 0.85) * float3(1.0, 0.85, 0.55) * (0.35 * exp(-sd * 4.0) + 0.15 * exp(-sd * 1.2));

    // Stars and a faint haze at night, hidden by cloud.
    float night = 1.0 - day;
    // Each star is a soft dot a few pixels wide, so it survives the car's video compression.
    float2 sg = fc / 6.0;
    float h = hash(floor(sg));
    float twinkle = 0.55 + 0.45 * sin(iTime * (1.0 + h * 3.0) + h * 40.0);
    float star = step(0.975, h) * smoothstep(0.45, 0.0, length(fract(sg) - 0.5));
    col += night * (1.0 - cloud) * star * twinkle * (1.0 - uv.y * 0.7) * float3(0.9, 0.95, 1.0);
    col += night * (1.0 - cloud) * 0.04 * fbm(p * 3.0 + float2(0.0, iTime * 0.003));

    // Clouds: a broad slow layer and a finer faster one, thinning toward the bottom.
    float2 cp = p * float2(1.6, 3.0);
    float c1 = fbm(cp * 1.3 + float2(iTime * 0.015, 0.0));
    float c2 = fbm(cp * 2.6 + float2(iTime * 0.03, 3.1));
    float edge = mix(0.72, 0.36, cloud);
    float density = smoothstep(edge, edge + 0.25, c1 * 0.65 + c2 * 0.35) * smoothstep(1.05, 0.35, uv.y);
    float3 lit = mix(float3(0.42, 0.45, 0.55), float3(0.97, 0.97, 1.0), day);
    float3 shadow = mix(mix(float3(0.06, 0.07, 0.12), float3(0.60, 0.64, 0.70), day), float3(0.22, 0.24, 0.28), storm);
    float3 cloudColor = mix(lit, shadow, clamp(smoothstep(0.2, 0.9, c2) * 0.8 + storm * 0.4, 0.0, 1.0));
    col = mix(col, cloudColor, density * mix(0.85, 0.95, cloud) * (day * 0.6 + 0.4));

    // Fog banks rolling slowly across the lower part.
    float f = fbm(float2(p.x * 1.2 + iTime * 0.02, p.y * 4.0));
    col = mix(col, mix(float3(0.32, 0.34, 0.40), float3(0.85, 0.87, 0.90), day), fog * (0.45 + 0.35 * f) * smoothstep(0.1, 0.9, uv.y + 0.2));

    // Rain at three depths: nearer streaks are bigger, brighter and faster.
    float streaks = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float scale = 1.0 + fi * 0.7;
        float2 q = fc / iResolution.y * float2(60.0 * scale, 3.0 * scale);
        q.x += q.y * 0.25;
        q.y -= iTime * (4.0 + fi * 2.0);
        float2 fr = fract(q);
        float drop = step(1.0 - rain * 0.6, hash(floor(q) + fi * 13.0));
        float line = smoothstep(0.08, 0.0, abs(fr.x - 0.5)) * smoothstep(0.0, 0.6, fr.y) * smoothstep(1.0, 0.7, fr.y);
        streaks += drop * line * (0.35 - fi * 0.08);
    }
    col += rain * streaks * mix(float3(0.55, 0.6, 0.7), float3(0.85, 0.9, 1.0), day);

    // Snow at three depths, each flake swaying as it falls.
    float flakes = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float scale = 8.0 + fi * 6.0;
        float2 q = p * scale;
        q.y -= iTime * (0.25 + fi * 0.12) * scale * 0.12;
        q.x += sin(q.y * 0.7 + iTime * 0.8 + fi) * 0.35;
        float2 cell = floor(q);
        float2 offset = float2(hash(cell + 3.1) - 0.5, hash(cell + 5.7) - 0.5) * 0.6;
        float d = length(fract(q) - 0.5 - offset);
        float r = mix(0.05, 0.12, hash(cell + 9.0)) * (1.0 - fi * 0.2);
        flakes += step(0.55, hash(cell + fi * 7.0)) * smoothstep(r, r * 0.3, d) * (0.9 - fi * 0.2);
    }
    col += snow * flakes;

    // Lightning: a sharp flash and a weaker flicker, every seven seconds.
    float cycle = mod(iTime, 7.0);
    col += storm * (exp(-cycle * 12.0) + 0.5 * exp(-abs(cycle - 0.25) * 30.0)) * 0.5;

    // A soft vignette, then dark mode's dimming.
    col *= 1.0 - 0.15 * length(uv - 0.5);
    col = mix(col, float3(0.0), dim);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
