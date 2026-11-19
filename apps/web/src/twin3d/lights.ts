// Glowing point lights (lanterns, street lights) drawn as soft sprites; they fade in at dusk (owner: Omar).
import * as THREE from "three";

export interface GlowLayer {
  points: THREE.Points;
  uniforms: { uNight: { value: number }; uScale: { value: number } };
  count: number;
}

export function buildGlow(positions: Float32Array, color: string, size: number, name: string, dayVisibility = 0.25): GlowLayer {
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  const seeds = new Float32Array(positions.length / 3);
  for (let i = 0; i < seeds.length; i++) seeds[i] = Math.random();
  g.setAttribute("seed", new THREE.BufferAttribute(seeds, 1));
  g.computeBoundingSphere();
  const uniforms = { uNight: { value: 0 }, uScale: { value: 800 } };
  const material = new THREE.ShaderMaterial({
    uniforms: { ...uniforms, uColor: { value: new THREE.Color(color) }, uSize: { value: size }, uDay: { value: dayVisibility } },
    vertexShader: `
      uniform float uScale; uniform float uSize; uniform float uNight;
      attribute float seed; varying float vSeed; varying float vFade;
      void main() {
        vSeed = seed;
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        gl_Position = projectionMatrix * mv;
        float d = -mv.z;
        // World-size sprites that never shrink below a couple of pixels, so far lights still sparkle.
        gl_PointSize = clamp(uSize * uScale / d, 1.5, 22.0) * (0.75 + 0.5 * uNight);
        vFade = clamp(1.0 - (d - 6000.0) / 6000.0, 0.0, 1.0);
      }`,
    fragmentShader: `
      uniform vec3 uColor; uniform float uNight; uniform float uDay;
      varying float vSeed; varying float vFade;
      void main() {
        vec2 c = gl_PointCoord - 0.5;
        float r = length(c) * 2.0;
        if (r > 1.0) discard;
        float core = smoothstep(0.45, 0.0, r);
        float halo = smoothstep(1.0, 0.2, r) * 0.45;
        float level = mix(uDay, 1.0, uNight) * (0.85 + 0.15 * vSeed);
        gl_FragColor = vec4(uColor * (core * 2.4 + halo) * level, (core + halo) * level * vFade);
      }`,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
  });
  const points = new THREE.Points(g, material);
  points.name = name;
  points.frustumCulled = false;
  points.renderOrder = 5;
  // Keep both objects pointing at the same uniform values.
  uniforms.uNight = material.uniforms.uNight as { value: number };
  uniforms.uScale = material.uniforms.uScale as { value: number };
  return { points, uniforms, count: positions.length / 3 };
}
