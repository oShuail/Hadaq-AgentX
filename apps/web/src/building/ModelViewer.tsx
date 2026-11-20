// 3D view of one building on its twin page (owner: Omar): our GLB with the wall-zone grid and attention
// boxes on its facade, lit by a fixed late-afternoon sun. Click a zone to inspect it.
import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { CSS2DRenderer } from "three/addons/renderers/CSS2DRenderer.js";

import { SITE } from "../config";
import { makeProjection } from "../site/projection";
import { type FocusPlacement, WallOverlay, loadFocusModel } from "../twin3d/focus";
import type { AttentionItem } from "../twin/WallZones";
import type { WallSpec } from "../types/contracts";

interface Props {
  url: string;
  wall: WallSpec | null;
  buildingId: string;
  zoneColors: Record<string, string>;
  attention: AttentionItem[];
  selectedZone: string | null;
  onSelectZone: (zoneId: string) => void;
}

/** The wall's placement with its middle at the scene origin (this page has no terrain). */
function localPlacement(wall: WallSpec): FocusPlacement {
  const proj = makeProjection(SITE.lat, SITE.lon);
  const [sx, sy] = proj.toLocal(wall.base_line.start.lon, wall.base_line.start.lat);
  const [ex, ey] = proj.toLocal(wall.base_line.end.lon, wall.base_line.end.lat);
  const az = (wall.azimuth_deg * Math.PI) / 180;
  return {
    midX: 0,
    midY: 0,
    az,
    ground: 0,
    out: [Math.sin(az), Math.cos(az)],
    left: [Math.cos(az), -Math.sin(az)],
    wallLength: Math.hypot(ex - sx, ey - sy),
    footprint: null,
  };
}

export default function ModelViewer(props: Props) {
  const host = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<WallOverlay | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
  const [status, setStatus] = useState("Loading the model");

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    el.appendChild(renderer.domElement);
    const labels = new CSS2DRenderer();
    labels.domElement.className = "twin-labels";
    el.appendChild(labels.domElement);

    const scene = new THREE.Scene();
    scene.background = new THREE.Color("#1f1610");
    scene.fog = new THREE.Fog("#1f1610", 140, 330);
    const camera = new THREE.PerspectiveCamera(38, 1, 0.5, 2000);
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.maxPolarAngle = THREE.MathUtils.degToRad(85);
    controls.minDistance = 15;
    controls.maxDistance = 320;
    controls.autoRotate = true;
    controls.autoRotateSpeed = 0.35;
    controls.addEventListener("start", () => (controls.autoRotate = false));

    scene.add(new THREE.HemisphereLight("#e6dccb", "#6b5440", 1.1));
    const sun = new THREE.DirectionalLight("#ffd2a1", 3.2);
    sun.position.set(-90, 70, 60);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -90, right: 90, top: 90, bottom: -90, near: 10, far: 400 });
    sun.shadow.bias = -0.0004;
    scene.add(sun);
    const ground = new THREE.Mesh(new THREE.CircleGeometry(400, 72), new THREE.MeshStandardMaterial({ color: "#8f7457", roughness: 1 }));
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.02;
    ground.receiveShadow = true;
    scene.add(ground);

    const wall = propsRef.current.wall;
    const place = wall ? localPlacement(wall) : null;
    let disposed = false;
    if (wall && place) {
      const overlay = new WallOverlay(wall, propsRef.current.buildingId, place);
      overlay.setVisible(true);
      overlay.setColors(propsRef.current.zoneColors);
      overlay.setAttention(propsRef.current.attention);
      overlay.highlight(propsRef.current.selectedZone);
      scene.add(overlay.group, overlay.attentionGroup);
      overlayRef.current = overlay;
    }
    // Start in front of the main facade, a little to its left, looking at the wall.
    const out = place ? new THREE.Vector3(place.out[0], 0, -place.out[1]) : new THREE.Vector3(0, 0, 1);
    const left = place ? new THREE.Vector3(place.left[0], 0, -place.left[1]) : new THREE.Vector3(1, 0, 0);
    const target = new THREE.Vector3(0, 6, 0).addScaledVector(out, -12);
    controls.target.copy(target);
    camera.position.copy(target).addScaledVector(out, 95).addScaledVector(left, 35).add(new THREE.Vector3(0, 38, 0));

    const facingWest: FocusPlacement = { midX: 0, midY: 0, az: 1.5 * Math.PI, ground: 0, out: [-1, 0], left: [0, 1], wallLength: 30, footprint: null };
    loadFocusModel(propsRef.current.url, place ?? facingWest)
      .then((model) => {
        if (disposed) return;
        scene.add(model);
        setStatus("");
      })
      .catch((err) => {
        console.error("Model failed to load", err);
        setStatus(`The model failed to load: ${String(err)}`);
      });

    const resize = () => {
      const w = Math.max(1, el.clientWidth);
      const h = Math.max(1, el.clientHeight);
      renderer.setSize(w, h);
      labels.setSize(w, h);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      overlayRef.current?.setResolution(w, h);
    };
    const ro = new ResizeObserver(resize);
    ro.observe(el);
    resize();

    // Click a zone panel to select it.
    const ray = new THREE.Raycaster();
    let down = { x: 0, y: 0 };
    const onDown = (e: PointerEvent) => (down = { x: e.clientX, y: e.clientY });
    const onUp = (e: PointerEvent) => {
      if (Math.hypot(e.clientX - down.x, e.clientY - down.y) > 5 || !overlayRef.current) return;
      const r = renderer.domElement.getBoundingClientRect();
      ray.setFromCamera(new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1), camera);
      const hit = ray.intersectObjects([...overlayRef.current.zones.values()], false)[0];
      if (hit?.object.userData.zoneId) propsRef.current.onSelectZone(hit.object.userData.zoneId as string);
    };
    renderer.domElement.addEventListener("pointerdown", onDown);
    renderer.domElement.addEventListener("pointerup", onUp);

    let raf = 0;
    const loop = () => {
      raf = requestAnimationFrame(loop);
      controls.update();
      renderer.render(scene, camera);
      labels.render(scene, camera);
    };
    loop();

    return () => {
      disposed = true;
      cancelAnimationFrame(raf);
      ro.disconnect();
      controls.dispose();
      overlayRef.current?.setAttention([]);
      overlayRef.current = null;
      scene.traverse((o) => {
        const m = o as THREE.Mesh;
        m.geometry?.dispose();
        for (const mat of Array.isArray(m.material) ? m.material : m.material ? [m.material] : []) mat.dispose();
      });
      renderer.dispose();
      el.replaceChildren();
    };
  }, [props.url]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => overlayRef.current?.setColors(props.zoneColors), [props.zoneColors]);
  useEffect(() => overlayRef.current?.setAttention(props.attention), [props.attention]);
  useEffect(() => overlayRef.current?.highlight(props.selectedZone), [props.selectedZone]);

  return (
    <div className="model-viewer">
      <div ref={host} className="model-canvas" />
      {status && <div className="map-status">{status}</div>}
    </div>
  );
}
