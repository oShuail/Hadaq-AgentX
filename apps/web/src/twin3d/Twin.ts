// The Hadaq 3D twin of Diriyah (owner: Omar). A three.js scene in local metres around At-Turaif built from the
// baked open-data pack (public/site/): imagery-draped terrain, low-detail buildings, date palms, roads,
// water, car parks, lanterns, simulated visitors, a real sun for any date and time, and our focus building
// with its wall zones. The React side (map/SiteMap.tsx) drives it through the public methods.
import * as THREE from "three";
import { MapControls } from "three/addons/controls/MapControls.js";
import { Sky } from "three/addons/objects/Sky.js";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { UnrealBloomPass } from "three/addons/postprocessing/UnrealBloomPass.js";
import { CSS2DRenderer } from "three/addons/renderers/CSS2DRenderer.js";
import { acceleratedRaycast, computeBoundsTree, disposeBoundsTree } from "three-mesh-bvh";

import { centroid, pointInRing, ringBounds } from "../site/poly";
import type { SiteBuilding } from "../site/types";
import { FOCUS_TWIN, TWINS } from "../twins";
import type { WallSpec } from "../types/contracts";
import { type BuildingLayer, buildBuildings } from "./buildings";
import type { AttentionItem } from "../twin/WallZones";
import { type FocusPlacement, WallOverlay, focusPlacement, loadFocusModel } from "./focus";
import { type InfraLayer, buildInfra } from "./infra";
import { LabelLayer } from "./labels";
import { turaifInfill } from "./infill";
import { type GlowLayer, buildGlow } from "./lights";
import { type PalmLayer, buildPalms } from "./palms";
import { type Site, loadSite } from "./siteData";
import { sunPosition } from "./sun";
import { buildTerrain, type TerrainLayer } from "./terrain";
import { type WalkerLayer, buildWalkers, crowdAt } from "./walkers";

THREE.BufferGeometry.prototype.computeBoundsTree = computeBoundsTree;
THREE.BufferGeometry.prototype.disposeBoundsTree = disposeBoundsTree;
THREE.Mesh.prototype.raycast = acceleratedRaycast;

export type LayerId =
  | "terrain" | "context" | "buildings" | "palms" | "roads" | "parking" | "water" | "walls"
  | "lanterns" | "streetlights" | "visitors" | "helipads" | "labels" | "twin";

export const ALL_LAYERS: { id: LayerId; label: string }[] = [
  { id: "terrain", label: "Terrain and imagery" },
  { id: "context", label: "Context terrain" },
  { id: "buildings", label: "Buildings" },
  { id: "palms", label: "Date palms" },
  { id: "roads", label: "Roads and paths" },
  { id: "parking", label: "Car parks and cars" },
  { id: "water", label: "Water" },
  { id: "walls", label: "City walls and towers" },
  { id: "lanterns", label: "Lanterns" },
  { id: "streetlights", label: "Street lights" },
  { id: "visitors", label: "Visitors (simulated)" },
  { id: "helipads", label: "Helipads" },
  { id: "labels", label: "Labels" },
  { id: "twin", label: "Hadaq wall zones" },
];

/** Layers that are built but switched off in the app for now (kept in the code to bring back later). */
export const DISABLED_LAYERS = new Set<LayerId>(["parking", "lanterns", "streetlights", "visitors", "helipads"]);

/** The layers the Explore panel offers. */
export const LAYERS = ALL_LAYERS.filter((l) => !DISABLED_LAYERS.has(l.id));

export type ViewId = "overview" | "turaif" | "focus" | "bujairi" | "wadi" | "plan";

export const VIEWS: { id: ViewId; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "turaif", label: "At-Turaif" },
  { id: "focus", label: FOCUS_TWIN.name },
  { id: "bujairi", label: "Bujairi Terrace" },
  { id: "wadi", label: "Wadi Hanifah" },
  { id: "plan", label: "Plan view" },
];

export type TwinSelection =
  // twinId: this building has its own twin page (twins.ts).
  | { kind: "building"; osmId: number | null; name: string; isFocus: boolean; focusLocked?: boolean; twinId?: string }
  | { kind: "zone"; zoneId: string };

export interface TwinStats {
  counts: Partial<Record<LayerId, number>>;
  model: { label: string; value: string }[];
  sources: string[];
  generated: string;
}

export interface TwinCallbacks {
  onStatus: (status: { loading: boolean; message: string; error?: boolean }) => void;
  onSelect: (selection: TwinSelection | null) => void;
  onStats: (stats: TwinStats) => void;
  onCoordinate: (lon: number, lat: number, height: number) => void;
}

interface Pose {
  target: THREE.Vector3;
  distance: number;
  heading: number; // compass degrees the camera looks towards
  pitch: number; // degrees below the horizon
}

const KIND_LABEL: Record<SiteBuilding["k"], string> = {
  najdi: "Najdi mud building",
  ruin: "Mud-brick ruin",
  modern: "Building",
  landmark: "Landmark",
};

// Which places get a small label: sights first, then places to eat or pray; shops and services stay off.
const POI_IMPORTANT = new Set(["museum", "attraction", "historic", "gallery", "viewpoint", "artwork", "arts_centre", "theatre", "palace", "castle"]);
const POI_MINOR = new Set(["place_of_worship", "park", "cafe", "restaurant", "hotel", "townhall", "information", "fountain"]);
const GENERIC = /^(exit|tickets?|water|toilets?|parking|park mosque|مسجد|بوفية)\b|bottles|refreshments|check$/i;
// Names that would be confused with our own twins (a cafe called "Salwa" 1 km north is not Qasr Salwa).
const CONFUSING = /^(salwa|سلوى)$/i;

/** The twin page a mapped building opens, if any (matched on its OSM name, see twins.ts). */
const twinFor = (b: SiteBuilding) => TWINS.find((t) => t.onMap !== "focus" && !!b.n && t.onMap.test(b.n)) ?? null;

const smooth = (a: number, b: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};

export class Twin {
  private renderer: THREE.WebGLRenderer;
  private labelsRenderer: CSS2DRenderer;
  private composer: EffectComposer;
  private bloom: UnrealBloomPass;
  private scene = new THREE.Scene();
  private camera: THREE.PerspectiveCamera;
  private controls: MapControls;
  private sky = new Sky();
  private sun = new THREE.DirectionalLight("#fff1e0", 3);
  private hemi = new THREE.HemisphereLight("#cfd8e6", "#6b5440", 1);
  private pmrem: THREE.PMREMGenerator;
  private envScene = new THREE.Scene();
  private envSky = new Sky();
  private envTarget: THREE.WebGLRenderTarget | null = null;
  private lastEnvSun = new THREE.Vector3(9, 9, 9);
  private tooltip: HTMLDivElement;
  private raf = 0;
  private disposed = false;
  private clock = new THREE.Clock();
  private lastLabels = 0;

  private site: Site | null = null;
  private terrain: TerrainLayer | null = null;
  private buildings: BuildingLayer | null = null;
  private palms: PalmLayer | null = null;
  private infra: InfraLayer | null = null;
  private lanterns: GlowLayer | null = null;
  private streetGlow: GlowLayer | null = null;
  private walkers: WalkerLayer | null = null;
  private labels = new LabelLayer();
  private overlay: WallOverlay | null = null;
  private focusModel: THREE.Group | null = null;
  private place: FocusPlacement | null = null;
  private heritageRings: number[][] = [];
  private poses = new Map<ViewId, Pose>();
  private groups = new Map<LayerId, THREE.Object3D[]>();
  private time = new Date();
  private crowd = 0.5;
  private fly: { from: Pose; to: Pose; t: number; duration: number } | null = null;
  private pointer = { x: 0, y: 0, downX: 0, downY: 0, downT: 0, moved: false, dirty: false, shift: false };
  private raycaster = new THREE.Raycaster();
  private hovered = -2;
  private wantZones = false;
  private layerState: Partial<Record<LayerId, boolean>> = {};
  private resizeObserver: ResizeObserver;
  private paused = false;
  private ratio = 1;
  private maxRatio = 1;
  private perf = { frames: 0, since: 0 };

  constructor(private container: HTMLElement, private cb: TwinCallbacks) {
    const renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: "high-performance" });
    this.maxRatio = Math.min(window.devicePixelRatio, 1.5);
    this.ratio = this.maxRatio;
    renderer.setPixelRatio(this.ratio);
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 0.9;
    renderer.domElement.className = "twin-canvas";
    container.appendChild(renderer.domElement);
    this.renderer = renderer;
    this.pmrem = new THREE.PMREMGenerator(renderer);

    this.labelsRenderer = new CSS2DRenderer();
    this.labelsRenderer.domElement.className = "twin-labels";
    container.appendChild(this.labelsRenderer.domElement);

    this.tooltip = document.createElement("div");
    this.tooltip.className = "twin-tooltip";
    container.appendChild(this.tooltip);

    this.camera = new THREE.PerspectiveCamera(42, 1, 1, 60000);
    this.camera.position.set(0, 3000, 4000);

    const controls = new MapControls(this.camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.09;
    controls.screenSpacePanning = false;
    controls.maxPolarAngle = THREE.MathUtils.degToRad(84);
    controls.minDistance = 12;
    controls.maxDistance = 16000;
    controls.zoomToCursor = true;
    controls.zoomSpeed = 1.2;
    controls.rotateSpeed = 0.55;
    controls.addEventListener("start", () => (this.fly = null));
    this.controls = controls;

    // Sky, sun and haze.
    this.sky.scale.setScalar(100000);
    this.sky.name = "sky";
    const su = this.sky.material.uniforms;
    su.turbidity.value = 7;
    su.rayleigh.value = 1.6;
    su.mieCoefficient.value = 0.007;
    su.mieDirectionalG.value = 0.86;
    this.scene.add(this.sky);
    this.envSky.scale.setScalar(10000);
    Object.assign(this.envSky.material.uniforms.turbidity, { value: 7 });
    this.envScene.add(this.envSky);
    this.scene.fog = new THREE.FogExp2("#c9b498", 0.000055);
    this.scene.add(this.hemi);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.6;
    this.scene.add(this.sun, this.sun.target);

    // Post: bloom for the lanterns at night.
    const rt = new THREE.WebGLRenderTarget(1, 1, { type: THREE.HalfFloatType, samples: 2 });
    this.composer = new EffectComposer(renderer, rt);
    this.composer.addPass(new RenderPass(this.scene, this.camera));
    this.bloom = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.35, 0.55, 0.9);
    this.composer.addPass(this.bloom);
    this.composer.addPass(new OutputPass());

    this.scene.add(this.labels.group);
    this.resize();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.bindPointer();
    this.loop();
  }

  // ------------------------------------------------------------------------------------------------
  // Loading

  async load(wall: WallSpec | null, buildingId: string, focusUrl: string) {
    const status = (message: string) => !this.disposed && this.cb.onStatus({ loading: true, message });
    status("Loading the site model");
    const site = await loadSite(status);
    if (this.disposed) return;
    if (!site) {
      this.cb.onStatus({
        loading: false,
        error: true,
        message: "No site data yet. With the dev server running, open /bake.html once to build it (see tools/site_data/README.md).",
      });
      return;
    }
    this.site = site;
    const { pack } = site;
    this.heritageRings = pack.areas.filter((a) => a.k === "heritage").map((a) => a.p);
    const inHeritage = (x: number, y: number) => this.heritageRings.some((r) => pointInRing(x, y, r));

    // The focus building: flatten the ground under it, and keep city blocks and palms out of its outline.
    if (wall) {
      this.place = await focusPlacement(site, wall, focusUrl);
      if (this.disposed) return;
      if (this.place.footprint) site.flatten(this.place.footprint, this.place.ground, 12);
    }
    const fp = this.place?.footprint ?? null;
    const fpBounds = fp ? ringBounds(fp) : null;
    const nearFocus = (x: number, y: number, pad: number) =>
      !!fp && !!fpBounds && x > fpBounds[0] - pad && x < fpBounds[2] + pad && y > fpBounds[1] - pad && y < fpBounds[3] + pad &&
      (pointInRing(x, y, fp) || pad > 0);
    const underFocus = (b: SiteBuilding) => {
      if (!fp) return false;
      const [cx, cy] = centroid(b.o);
      if (pointInRing(cx, cy, fp)) return true;
      for (let i = 0; i < b.o.length; i += 2) if (pointInRing(b.o[i], b.o[i + 1], fp)) return true;
      return false;
    };

    // Fill the unmapped parts of At-Turaif with illustrative courtyard ruins (kept clear of the focus building).
    pack.buildings = [...pack.buildings.filter((b) => !b.id.startsWith("i")), ...turaifInfill(pack, (x, y) => nearFocus(x, y, 10))];

    status("Draping the imagery");
    this.terrain = await buildTerrain(site, this.renderer);
    if (this.disposed) return;
    this.scene.add(this.terrain.core, this.terrain.wide);
    this.terrain.core.geometry.computeBoundsTree();

    status("Raising the buildings");
    await new Promise((r) => setTimeout(r, 0));
    this.buildings = buildBuildings(site, underFocus);
    this.scene.add(this.buildings.group);
    for (const m of this.buildings.meshes) m.geometry.computeBoundsTree();

    status("Planting palms and laying roads");
    await new Promise((r) => setTimeout(r, 0));
    this.palms = buildPalms(site, (x, y) => nearFocus(x, y, 4));
    this.scene.add(this.palms.group);
    this.infra = buildInfra(site, inHeritage, (x, y) => nearFocus(x, y, 2));
    const { roads, water, parking, walls, helipads } = this.infra;
    this.scene.add(roads, water, parking, walls, helipads);

    this.lanterns = buildGlow(new Float32Array([...this.buildings.lanterns, ...this.infra.pathLights]), "#ffbf6b", 1.4, "lanterns", 0.15);
    this.streetGlow = buildGlow(this.infra.streetLights, "#ffd9a0", 2.2, "street-lights", 0);
    this.scene.add(this.lanterns.points, this.streetGlow.points);
    this.walkers = buildWalkers(this.infra.pathsForWalkers, site.ground);
    if (this.walkers) this.scene.add(this.walkers.mesh);

    // Labels: districts and places from the data, plus the live twin.
    const clean = (n: string) => n.replace(/ District in ad-Dir.iyah$/i, "").trim();
    const areaNames = new Set<string>();
    for (const a of pack.areas)
      if (a.n) {
        const [x, y] = centroid(a.p);
        areaNames.add(clean(a.n));
        this.labels.add({ text: clean(a.n), kind: "area", position: new THREE.Vector3(x, site.ground(x, y) + 40, -y), priority: a.k === "heritage" ? 9 : 6 });
      }
    for (const p of pack.pois) {
      const name = clean(p.n);
      if (areaNames.has(name) || name.length < 3 || /^\d+$/.test(name) || GENERIC.test(name) || CONFUSING.test(name)) continue;
      const big = !!p.big;
      const important = POI_IMPORTANT.has(p.k);
      if (!big && !important && !POI_MINOR.has(p.k)) continue;
      this.labels.add({
        text: name,
        kind: big ? "area" : "poi",
        position: new THREE.Vector3(p.x, site.ground(p.x, p.y) + (big ? 35 : 12), -p.y),
        priority: big ? (p.k === "attraction" || p.k === "wadi" ? 8 : 7) : important ? 4 : 2,
      });
    }

    // Focus building, zones and its label.
    if (wall && this.place) {
      this.overlay = new WallOverlay(wall, buildingId, this.place);
      this.scene.add(this.overlay.group, this.overlay.attentionGroup);
      this.overlay.setVisible(this.wantZones);
      this.overlay.setResolution(this.container.clientWidth, this.container.clientHeight);
      this.labels.add({
        text: FOCUS_TWIN.ar ? `${FOCUS_TWIN.name} · ${FOCUS_TWIN.ar}` : FOCUS_TWIN.name,
        sub: `${buildingId} live twin: sensors, camera, agent`,
        kind: "focus",
        position: new THREE.Vector3(this.place.midX - this.place.out[0] * 25, this.place.ground + 32, -(this.place.midY - this.place.out[1] * 25)),
        priority: 10,
        onClick: () => this.selectFocus(),
      });
      if (focusUrl) {
        status("Loading the focus building model");
        try {
          this.focusModel = await loadFocusModel(focusUrl, this.place);
          if (this.disposed) return;
          this.scene.add(this.focusModel);
          this.setTime(this.time); // light it for the current hour
          this.focusModel.traverse((o) => (o as THREE.Mesh).isMesh && (o as THREE.Mesh).geometry.computeBoundsTree());
        } catch (err) {
          console.error("Focus model failed to load", err);
        }
      }
    }

    this.groups = new Map<LayerId, THREE.Object3D[]>([
      ["terrain", [this.terrain.core]],
      ["context", [this.terrain.wide]],
      ["buildings", [this.buildings.group]],
      ["palms", [this.palms.group]],
      ["roads", [roads]],
      ["parking", [parking]],
      ["water", [water]],
      ["walls", [walls]],
      ["lanterns", [this.lanterns.points]],
      ["streetlights", [this.streetGlow.points]],
      ["visitors", this.walkers ? [this.walkers.mesh] : []],
      ["helipads", [helipads]],
      ["labels", []],
      ["twin", this.overlay ? [this.overlay.attentionGroup] : []],
    ]);
    for (const { id } of ALL_LAYERS) this.setLayer(id, this.layerState[id] ?? true);

    this.makePoses();
    this.setTime(this.time);
    this.jumpTo(this.poses.get("overview")!);
    this.flyTo("turaif", 4.5);

    const c = this.buildings.counts;
    this.cb.onStats({
      counts: {
        buildings: c.total,
        palms: this.palms.count,
        roads: this.infra.counts.roads + this.infra.counts.paths,
        water: this.infra.counts.water,
        walls: this.infra.counts.walls + this.infra.counts.towers,
        ...(DISABLED_LAYERS.has("parking") ? {} : { parking: this.infra.counts.parkingBays }),
        ...(DISABLED_LAYERS.has("lanterns") ? {} : { lanterns: this.lanterns.count }),
        ...(DISABLED_LAYERS.has("streetlights") ? {} : { streetlights: this.streetGlow.count }),
        ...(DISABLED_LAYERS.has("visitors") ? {} : { visitors: this.walkers?.max ?? 0 }),
        ...(DISABLED_LAYERS.has("helipads") ? {} : { helipads: this.infra.counts.helipads }),
      },
      model: [
        { label: "buildings", value: `${c.total.toLocaleString()} · Najdi ${c.najdi.toLocaleString()} · ruins ${c.ruin.toLocaleString()}` },
        { label: "palms", value: this.palms.count.toLocaleString() },
        { label: "places", value: pack.pois.length.toLocaleString() },
        { label: "twin pages", value: TWINS.map((t) => t.name).join(", ") },
        { label: "heights", value: "estimated unless OSM has them" },
      ],
      sources: pack.sources,
      generated: pack.generated,
    });
    this.cb.onStatus({ loading: false, message: "" });
  }

  // ------------------------------------------------------------------------------------------------
  // Views

  private findPlace(re: RegExp): [number, number] | null {
    if (!this.site) return null;
    const a = this.site.pack.areas.find((x) => re.test(`${x.n ?? ""} ${x.ar ?? ""}`));
    if (a) return centroid(a.p);
    const p = this.site.pack.pois.find((x) => re.test(`${x.n} ${x.ar ?? ""}`));
    if (p) return [p.x, p.y];
    const w = this.site.pack.waterways.find((x) => re.test(x.n ?? ""));
    if (w) {
      const mid = Math.floor(w.p.length / 4) * 2;
      return [w.p[mid], w.p[mid + 1]];
    }
    return null;
  }

  private makePoses() {
    const site = this.site!;
    const at = (x: number, y: number, lift = 0) => new THREE.Vector3(x, site.ground(x, y) + lift, -y);
    const turaif = this.findPlace(/turaif|طريف/i) ?? [0, 0];
    const bujairi = this.findPlace(/bujairi|bujayri|بجيري/i) ?? [380, 120];
    const wadi = this.findPlace(/hanifa|حنيفة/i) ?? [250, -350];
    this.poses.set("overview", { target: at(150, 700), distance: 4300, heading: 15, pitch: 24 });
    this.poses.set("turaif", { target: at(turaif[0], turaif[1]), distance: 820, heading: 32, pitch: 27 });
    this.poses.set("bujairi", { target: at(bujairi[0], bujairi[1]), distance: 650, heading: 335, pitch: 28 });
    this.poses.set("wadi", { target: at(wadi[0], wadi[1]), distance: 1500, heading: 150, pitch: 30 });
    this.poses.set("plan", { target: at(0, 150), distance: 3800, heading: 0, pitch: 89.5 });
    if (this.place) {
      const p = this.place;
      // Stand in front of the main facade, looking at it.
      const cx = p.midX - p.out[0] * 18;
      const cy = p.midY - p.out[1] * 18;
      const heading = ((p.az * 180) / Math.PI + 180) % 360;
      this.poses.set("focus", { target: new THREE.Vector3(cx, p.ground + 9, -cy), distance: 135, heading, pitch: 17 });
    } else this.poses.set("focus", this.poses.get("turaif")!);
  }

  private poseToCamera(p: Pose, outPos: THREE.Vector3) {
    const h = THREE.MathUtils.degToRad(p.heading);
    const v = THREE.MathUtils.degToRad(p.pitch);
    const dir = new THREE.Vector3(Math.sin(h) * Math.cos(v), -Math.sin(v), -Math.cos(h) * Math.cos(v));
    return outPos.copy(p.target).addScaledVector(dir, -p.distance);
  }

  private currentPose(): Pose {
    const off = this.camera.position.clone().sub(this.controls.target);
    const distance = off.length();
    const pitch = THREE.MathUtils.radToDeg(Math.asin(Math.max(-1, Math.min(1, off.y / distance))));
    const heading = (THREE.MathUtils.radToDeg(Math.atan2(-off.x, off.z)) + 360) % 360;
    return { target: this.controls.target.clone(), distance, heading, pitch };
  }

  private jumpTo(p: Pose) {
    this.controls.target.copy(p.target);
    this.poseToCamera(p, this.camera.position);
    this.camera.lookAt(p.target);
    this.controls.update();
  }

  flyTo(view: ViewId, duration = 2.6) {
    const to = this.poses.get(view);
    if (!to) return;
    const from = this.currentPose();
    // Turn the short way round.
    let dh = to.heading - from.heading;
    if (dh > 180) dh -= 360;
    if (dh < -180) dh += 360;
    this.fly = { from, to: { ...to, heading: from.heading + dh }, t: 0, duration };
  }

  private stepFly(dt: number) {
    if (!this.fly) return;
    const f = this.fly;
    f.t = Math.min(1, f.t + dt / f.duration);
    const e = f.t < 0.5 ? 4 * f.t ** 3 : 1 - (-2 * f.t + 2) ** 3 / 2;
    // Rise out and come back in on long trips, so the camera does not skim the rooftops.
    const span = f.from.target.distanceTo(f.to.target);
    const arc = Math.sin(Math.PI * e) * Math.min(1, span / 2500) * 0.6;
    const pose: Pose = {
      target: f.from.target.clone().lerp(f.to.target, e),
      distance: Math.exp(THREE.MathUtils.lerp(Math.log(f.from.distance), Math.log(f.to.distance), e)) * (1 + arc),
      heading: THREE.MathUtils.lerp(f.from.heading, f.to.heading, e),
      pitch: THREE.MathUtils.lerp(f.from.pitch, f.to.pitch, e),
    };
    this.jumpTo(pose);
    if (f.t >= 1) this.fly = null;
  }

  // ------------------------------------------------------------------------------------------------
  // Time of day

  setTime(date: Date) {
    this.time = date;
    const lat = this.site?.pack.origin.lat ?? 24.7336;
    const lon = this.site?.pack.origin.lon ?? 46.5725;
    const { altitude, azimuth } = sunPosition(date, lat, lon);
    const a = THREE.MathUtils.degToRad(altitude);
    const z = THREE.MathUtils.degToRad(azimuth);
    const sunDir = new THREE.Vector3(Math.sin(z) * Math.cos(a), Math.sin(a), -Math.cos(z) * Math.cos(a));
    this.sky.material.uniforms.sunPosition.value.copy(sunDir);

    const day = smooth(-4, 10, altitude); // 0 night, 1 day
    const night = smooth(3, -7, altitude);
    const golden = 1 - smooth(4, 28, altitude);
    // The directional light is the sun by day and a faint cool moon by night.
    const moonDir = new THREE.Vector3(-0.35, 0.75, 0.55).normalize();
    this.sun.userData.dir = altitude > -2 ? sunDir : moonDir;
    const warm = new THREE.Color("#ff9e57").lerp(new THREE.Color("#fff3e3"), 1 - golden);
    this.sun.color.copy(altitude > -2 ? warm : new THREE.Color("#9fb4dc"));
    this.sun.intensity = altitude > -2 ? 3.6 * (0.35 + 0.65 * smooth(-2, 10, altitude)) * smooth(-2, 1, altitude) : 0.35 * night;
    this.hemi.color.set("#3a4a74").lerp(new THREE.Color("#d6dceb"), day);
    this.hemi.groundColor.set("#2a2026").lerp(new THREE.Color("#7b5f45"), day);
    this.hemi.intensity = 0.55 + day * 0.45;
    const fog = new THREE.Color("#121826").lerp(new THREE.Color("#d39b66"), day).lerp(new THREE.Color("#cdbca2"), day * (1 - golden));
    (this.scene.fog as THREE.FogExp2).color.copy(fog);
    this.sky.visible = altitude > -9;
    this.scene.background = new THREE.Color("#0b1020");
    // A high midday sun on pale sand blows out: bring the exposure down as the sun climbs.
    this.renderer.toneMappingExposure = 0.95 - 0.25 * smooth(12, 55, altitude) + night * 0.55;
    // Bloom is for the lights at night; by day it bleeds bright sand into a white haze.
    this.bloom.strength = 0.08 * golden * (1 - night) + night * 0.6;
    this.bloom.enabled = this.bloom.strength > 0.01;

    if (this.lanterns) this.lanterns.uniforms.uNight.value = night;
    if (this.buildings) this.buildings.uniforms.uNight.value = night;
    // Floodlight the focus building at night like its neighbours.
    this.focusModel?.traverse((o) => {
      const m = (o as THREE.Mesh).material as THREE.MeshStandardMaterial | undefined;
      if (!m?.isMeshStandardMaterial) return;
      if (m.map && m.emissiveMap !== m.map) {
        m.emissiveMap = m.map;
        m.needsUpdate = true;
      }
      m.emissive.setRGB(1, 0.62, 0.36);
      m.emissiveIntensity = night * 0.55;
    });
    if (this.streetGlow) {
      this.streetGlow.uniforms.uNight.value = night;
      this.streetGlow.points.visible = night > 0.02 && this.layerState.streetlights !== false && !DISABLED_LAYERS.has("streetlights");
    }
    const hour = Number(new Intl.DateTimeFormat("en-GB", { hour: "numeric", hour12: false, timeZone: "Asia/Riyadh" }).format(date)) % 24;
    const minute = date.getUTCMinutes();
    this.crowd = crowdAt(hour + minute / 60);
    this.infra?.setCarOccupancy(0.25 + this.crowd * 0.7);

    // Environment lighting from the sky, refreshed when the sun has moved noticeably.
    if (sunDir.distanceTo(this.lastEnvSun) > 0.04) {
      this.lastEnvSun.copy(sunDir);
      this.envSky.material.uniforms.sunPosition.value.copy(sunDir);
      this.envTarget?.dispose();
      this.envTarget = this.pmrem.fromScene(this.envScene);
      this.scene.environment = this.envTarget.texture;
      this.scene.environmentIntensity = 0.15 + day * 0.15;
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Layers and the focus overlay

  setLayer(id: LayerId, requested: boolean) {
    const visible = requested && !DISABLED_LAYERS.has(id);
    this.layerState[id] = visible;
    if (id === "labels") this.labels.enabled = visible;
    for (const o of this.groups.get(id) ?? []) o.visible = visible;
  }

  /** Stop drawing while the map is hidden (e.g. a building page is open). */
  setPaused(p: boolean) {
    this.paused = p;
    if (!p) this.resize();
  }

  setZonesVisible(v: boolean) {
    this.wantZones = v;
    this.overlay?.setVisible(v);
  }
  setZoneColors(colors: Record<string, string>) {
    this.overlay?.setColors(colors);
  }
  setSelectedZone(id: string | null) {
    this.overlay?.highlight(id);
  }
  setAttention(items: AttentionItem[]) {
    this.overlay?.setAttention(items);
  }

  private selectFocus() {
    if (this.buildings) this.buildings.uniforms.uSelected.value = -1;
    this.cb.onSelect({ kind: "building", osmId: null, name: FOCUS_TWIN.name, isFocus: true, focusLocked: true, twinId: FOCUS_TWIN.id });
  }

  // ------------------------------------------------------------------------------------------------
  // Pointer: hover tooltip, click to select, shift + click for coordinates

  private bindPointer() {
    const el = this.renderer.domElement;
    el.addEventListener("pointermove", (e) => {
      const r = el.getBoundingClientRect();
      this.pointer.x = e.clientX - r.left;
      this.pointer.y = e.clientY - r.top;
      if (Math.hypot(e.clientX - this.pointer.downX, e.clientY - this.pointer.downY) > 5) this.pointer.moved = true;
      this.pointer.dirty = true;
    });
    el.addEventListener("pointerleave", () => {
      this.tooltip.style.opacity = "0";
      this.setHover(-2);
    });
    el.addEventListener("pointerdown", (e) => {
      this.pointer.downX = e.clientX;
      this.pointer.downY = e.clientY;
      this.pointer.downT = performance.now();
      this.pointer.moved = false;
    });
    el.addEventListener("pointerup", (e) => {
      if (e.button !== 0 || this.pointer.moved || performance.now() - this.pointer.downT > 450) return;
      this.click(e.shiftKey);
    });
  }

  private ndc(): THREE.Vector2 {
    return new THREE.Vector2((this.pointer.x / this.container.clientWidth) * 2 - 1, -(this.pointer.y / this.container.clientHeight) * 2 + 1);
  }

  private pick(withTerrain: boolean) {
    this.raycaster.setFromCamera(this.ndc(), this.camera);
    this.raycaster.firstHitOnly = true;
    const targets: THREE.Object3D[] = [];
    if (this.overlay?.group.visible) targets.push(...this.overlay.zones.values());
    if (this.focusModel) targets.push(this.focusModel);
    if (this.buildings?.group.visible) targets.push(...this.buildings.meshes);
    if (withTerrain && this.terrain) targets.push(this.terrain.core);
    const hits = this.raycaster.intersectObjects(targets, true);
    return hits[0] ?? null;
  }

  private describe(hit: THREE.Intersection | null): { building?: number; focus?: boolean; zone?: string } {
    if (!hit) return {};
    const o = hit.object;
    if (o.userData.zoneId) return { zone: o.userData.zoneId as string };
    if (o.userData.focus) return { focus: true };
    const bid = (o as THREE.Mesh).geometry?.getAttribute("bid");
    if (bid && hit.face) return { building: Math.round(bid.getX(hit.face.a)) };
    return {};
  }

  private setHover(index: number) {
    if (this.hovered === index) return;
    this.hovered = index;
    if (this.buildings) this.buildings.uniforms.uHover.value = index;
    this.renderer.domElement.style.cursor = index !== -2 ? "pointer" : "";
  }

  private hover() {
    if (!this.pointer.dirty || !this.buildings) return;
    this.pointer.dirty = false;
    const what = this.describe(this.pick(false));
    let title = "";
    let sub = "";
    if (what.zone) {
      title = what.zone;
      sub = "Wall zone · click for its readings";
      this.setHover(-1);
    } else if (what.focus) {
      title = FOCUS_TWIN.ar ? `${FOCUS_TWIN.name} (${FOCUS_TWIN.ar})` : FOCUS_TWIN.name;
      sub = "Live twin · click to open its page";
      this.setHover(-1);
    } else if (what.building !== undefined) {
      const info = this.buildings.infos[what.building];
      const b = info?.b;
      if (b) {
        title = b.n ?? KIND_LABEL[b.k];
        const twin = twinFor(b);
        sub = twin
          ? `Twin page: ${twin.name} · click to open`
          : `${b.n ? KIND_LABEL[b.k] + " · " : ""}about ${Math.round(b.h)} m${b.hs === "estimated" ? " (estimated)" : ""}`;
      }
      this.setHover(what.building);
    } else this.setHover(-2);
    if (title) {
      this.tooltip.innerHTML = "<strong></strong><span></span>";
      this.tooltip.querySelector("strong")!.textContent = title;
      this.tooltip.querySelector("span")!.textContent = sub;
      this.tooltip.style.transform = `translate(${this.pointer.x + 16}px, ${this.pointer.y + 14}px)`;
      this.tooltip.style.opacity = "1";
    } else this.tooltip.style.opacity = "0";
  }

  private click(shift: boolean) {
    const hit = this.pick(shift);
    if (shift) {
      if (hit && this.site) {
        const [lon, lat] = this.site.proj.toLonLat(hit.point.x, -hit.point.z);
        this.cb.onCoordinate(lon, lat, hit.point.y + this.site.pack.origin.h_msl);
      }
      return;
    }
    const what = this.describe(hit);
    if (what.zone) {
      this.cb.onSelect({ kind: "zone", zoneId: what.zone });
    } else if (what.focus) {
      this.selectFocus();
    } else if (what.building !== undefined && this.buildings) {
      const b = this.buildings.infos[what.building]?.b;
      if (!b) return;
      this.buildings.uniforms.uSelected.value = what.building;
      const osm = /^[wr]\d+$/.test(b.id) ? Number(b.id.slice(1)) : null;
      const twin = twinFor(b);
      this.cb.onSelect({ kind: "building", osmId: osm, name: b.n ?? KIND_LABEL[b.k], isFocus: false, ...(twin ? { twinId: twin.id } : {}) });
    } else {
      if (this.buildings) this.buildings.uniforms.uSelected.value = -1;
      this.cb.onSelect(null);
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Frame loop

  private resize() {
    const w = Math.max(1, this.container.clientWidth);
    const h = Math.max(1, this.container.clientHeight);
    this.renderer.setPixelRatio(this.ratio);
    this.composer.setPixelRatio(this.ratio);
    this.renderer.setSize(w, h, false);
    this.renderer.domElement.style.width = "100%";
    this.renderer.domElement.style.height = "100%";
    this.composer.setSize(w, h);
    this.labelsRenderer.setSize(w, h);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.overlay?.setResolution(w, h);
  }

  private loop = () => {
    if (this.disposed) return;
    this.raf = requestAnimationFrame(this.loop);
    if (this.paused) {
      this.clock.getDelta(); // no jump when it comes back
      return;
    }
    const dt = Math.min(0.1, this.clock.getDelta());
    const t = this.clock.elapsedTime;
    this.stepFly(dt);
    this.controls.update();

    if (this.site) {
      // Keep the orbit point on the ground and inside the model area.
      const tg = this.controls.target;
      const b = this.site.pack.boxes.wide;
      tg.x = Math.max(b.x0 * 0.8, Math.min(b.x1 * 0.8, tg.x));
      tg.z = Math.max(-b.y1 * 0.8, Math.min(-b.y0 * 0.8, tg.z));
      if (!this.fly) {
        const g = this.site.ground(tg.x, -tg.z);
        tg.y += (g - tg.y) * 0.15;
      }
      // Never go underground.
      const under = this.site.ground(this.camera.position.x, -this.camera.position.z) + 3;
      if (this.camera.position.y < under) this.camera.position.y = under;
    }

    const dist = this.camera.position.distanceTo(this.controls.target);
    this.camera.near = Math.max(0.5, dist / 400);
    this.camera.far = 60000;
    this.camera.updateProjectionMatrix();

    // Shadows follow the view.
    const dir = (this.sun.userData.dir as THREE.Vector3 | undefined) ?? new THREE.Vector3(0.3, 0.8, 0.4);
    const half = THREE.MathUtils.clamp(dist * 0.8, 90, 2600);
    const sc = this.sun.shadow.camera;
    sc.left = -half;
    sc.right = half;
    sc.top = half;
    sc.bottom = -half;
    sc.near = 10;
    sc.far = 9000;
    sc.updateProjectionMatrix();
    this.sun.target.position.copy(this.controls.target);
    this.sun.position.copy(this.controls.target).addScaledVector(dir, 4000);
    this.sun.shadow.normalBias = 0.3 + half / 900;

    if (this.buildings) this.buildings.uniforms.uTime.value = t;
    if (this.palms) {
      this.palms.uniforms.uTime.value = t;
      this.palms.update(this.camera);
    }
    const scale = this.container.clientHeight / (2 * Math.tan(THREE.MathUtils.degToRad(this.camera.fov / 2)));
    if (this.lanterns) this.lanterns.uniforms.uScale.value = scale;
    if (this.streetGlow) this.streetGlow.uniforms.uScale.value = scale;
    if (this.walkers?.mesh.visible) this.walkers.update(dt, this.crowd);
    this.hover();

    if (t - this.lastLabels > 0.15) {
      this.lastLabels = t;
      this.labels.update(this.camera, this.container.clientWidth, this.container.clientHeight);
    }
    this.composer.render();
    this.labelsRenderer.render(this.scene, this.camera);
    this.adaptQuality(t);
  };

  /** Lower the render resolution on slow GPUs (laptops with integrated graphics), raise it again when there is headroom. */
  private adaptQuality(t: number) {
    this.perf.frames++;
    if (!this.perf.since) this.perf.since = t;
    const span = t - this.perf.since;
    if (span < 2) return;
    const fps = this.perf.frames / span;
    this.perf.frames = 0;
    this.perf.since = t;
    // Below ~8 fps the browser is throttling a hidden or covered window: that says nothing about the GPU.
    if (!this.site || fps < 8) return;
    let next = this.ratio;
    if (fps < 28 && this.ratio > 1) next = Math.max(1, this.ratio - 0.25);
    else if (fps < 16 && this.ratio > 0.75) next = 0.75;
    else if (fps > 55 && this.ratio < this.maxRatio) next = Math.min(this.maxRatio, this.ratio + 0.25);
    if (next !== this.ratio) {
      this.ratio = next;
      this.resize();
    }
  }

  dispose() {
    this.disposed = true;
    cancelAnimationFrame(this.raf);
    this.resizeObserver.disconnect();
    this.controls.dispose();
    this.scene.traverse((o) => {
      const m = o as THREE.Mesh;
      m.geometry?.dispose();
      const mats = Array.isArray(m.material) ? m.material : m.material ? [m.material] : [];
      for (const mat of mats) {
        for (const v of Object.values(mat)) if (v instanceof THREE.Texture) v.dispose();
        mat.dispose();
      }
    });
    this.envTarget?.dispose();
    this.pmrem.dispose();
    this.composer.dispose();
    this.renderer.dispose();
    this.renderer.forceContextLoss();
    this.container.replaceChildren();
  }
}
