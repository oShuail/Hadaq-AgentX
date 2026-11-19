// Diriyah site map (shell: Turky; wall zones and focus model: Omar).
import {
  Cartesian2,
  Cartesian3,
  Cartographic,
  Cesium3DTileFeature,
  type Cesium3DTileset,
  Cesium3DTileStyle,
  Color,
  Entity,
  HeadingPitchRange,
  Ion,
  JulianDate,
  KeyboardEventModifier,
  Math as CesiumMath,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  Terrain,
  Viewer,
  createGooglePhotorealistic3DTileset,
  createOsmBuildingsAsync,
  defined,
} from "cesium";
import "cesium/Build/Cesium/Widgets/widgets.css";
import { useEffect, useRef, useState } from "react";

import { CESIUM_ION_TOKEN, DISTRICT_URL, FOCUS_MODEL_URL, FOCUS_OSM_ID, GOOGLE_3D, SITE } from "../config";
import { type District, loadDistrict, outlineBuilding, outsideDistrictCondition, pickedDistrictBuilding, setBuildingVisible } from "../twin/district";
import { offsetLonLat, setSurfaceTiles } from "../twin/geo";
import { clipUnderFootprint, placeFocusModel } from "../twin/focusModel";
import { type AttentionItem, type AttentionLayer, colorZones, createAttentionLayer, createWallZones, highlightZone, showZones } from "../twin/WallZones";
import type { WallSpec } from "../types/contracts";

export type Selection =
  // focusLocked: this really is the configured focus building (not just "any building" in dev mode), so its scan opens.
  | { kind: "building"; osmId: number | null; name: string; isFocus: boolean; focusLocked?: boolean }
  | { kind: "zone"; zoneId: string };

export type FlyTarget = "overview" | "turaif" | "focus";

interface Props {
  buildingId: string;
  wall: WallSpec | null;
  zoneColors: Record<string, string>;
  attention: AttentionItem[];
  zonesVisible: boolean;
  selectedZone: string | null;
  fly: { target: FlyTarget; nonce: number };
  clockIso: string | null;
  onSelect: (selection: Selection) => void;
  onCoordinate: (lon: number, lat: number, height: number) => void;
}

const MUD = "color('#C9A27E')";
const HIGHLIGHT = Color.fromCssColorString("#E0B15A");

/** World point under a click: depth pick first (buildings, Google mesh), then the terrain, then the ellipsoid. */
function clickPoint(viewer: Viewer, screen: Cartesian2): Cartesian3 | undefined {
  const scene = viewer.scene;
  const onSurface = scene.pickPositionSupported ? scene.pickPosition(screen) : undefined;
  if (defined(onSurface)) return onSurface;
  const ray = viewer.camera.getPickRay(screen);
  const onGround = ray && scene.globe.show ? scene.globe.pick(ray, scene) : undefined;
  return onGround ?? viewer.camera.pickEllipsoid(screen) ?? undefined;
}

/** True when a clicked point is within 40 m of the middle of the focus wall's base. */
function nearWall(point: Cartesian3, wall: WallSpec): boolean {
  const { start, end } = wall.base_line;
  const height = Cartographic.fromCartesian(point).height;
  const mid = Cartesian3.fromDegrees((start.lon + end.lon) / 2, (start.lat + end.lat) / 2, height);
  return Cartesian3.distance(point, mid) < 40;
}

function flyTo(viewer: Viewer, target: FlyTarget, wall: WallSpec | null): void {
  if (target === "focus" && wall) {
    const midLon = (wall.base_line.start.lon + wall.base_line.end.lon) / 2;
    const midLat = (wall.base_line.start.lat + wall.base_line.end.lat) / 2;
    const [camLon, camLat] = offsetLonLat(midLon, midLat, 70, wall.azimuth_deg); // stand 70 m in front of the facade
    viewer.camera.flyTo({
      destination: Cartesian3.fromDegrees(camLon, camLat, 700 + 35),
      orientation: { heading: CesiumMath.toRadians((wall.azimuth_deg + 180) % 360), pitch: CesiumMath.toRadians(-22), roll: 0 },
      duration: 2.5,
    });
    return;
  }
  const height = target === "overview" ? 3500 : 1300;
  const [lon, lat] = offsetLonLat(SITE.lon, SITE.lat, target === "overview" ? 3500 : 1200, 180); // look north from the south
  viewer.camera.flyTo({
    destination: Cartesian3.fromDegrees(lon, lat, height),
    orientation: { heading: 0, pitch: CesiumMath.toRadians(-38), roll: 0 },
    duration: 2.5,
  });
}

export default function SiteMap(props: Props) {
  const container = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<Viewer | null>(null);
  const zonesRef = useRef<Map<string, Entity>>(new Map());
  const attentionRef = useRef<AttentionLayer | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
  const districtRef = useRef<District | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const focusModelRef = useRef<Promise<unknown> | null>(null);
  const googleTilesRef = useRef<Cesium3DTileset | null>(null);
  const focusModelInstance = useRef<Awaited<ReturnType<typeof placeFocusModel>> | null>(null);

  // Our own model of the focus building loads only when the user opens it, then replaces the district copy.
  const ensureFocusModel = (viewer: Viewer) => {
    const wall = propsRef.current.wall;
    if (focusModelRef.current || !FOCUS_MODEL_URL || !wall || viewer.isDestroyed()) return;
    const { start, end } = wall.base_line;
    const loading: Promise<unknown> = placeFocusModel(viewer, FOCUS_MODEL_URL, (start.lon + end.lon) / 2, (start.lat + end.lat) / 2, wall.azimuth_deg)
      .then((model) => {
        if (viewer.isDestroyed()) return;
        focusModelInstance.current = model;
        if (districtRef.current && FOCUS_OSM_ID !== null) setBuildingVisible(districtRef.current, FOCUS_OSM_ID, false);
        if (googleTilesRef.current) return clipUnderFootprint(googleTilesRef.current, FOCUS_MODEL_URL, wall);
      })
      .catch((err) => {
        if (focusModelRef.current === loading) focusModelRef.current = null; // only clear our own attempt
        if (!viewer.isDestroyed()) console.error("Focus model failed to load", err);
      });
    focusModelRef.current = loading;
  };

  // Create the viewer once.
  useEffect(() => {
    if (!container.current) return;
    (window as unknown as { CESIUM_BASE_URL: string }).CESIUM_BASE_URL = CESIUM_BASE_URL;
    if (CESIUM_ION_TOKEN) Ion.defaultAccessToken = CESIUM_ION_TOKEN;

    const viewer = new Viewer(container.current, {
      terrain: Terrain.fromWorldTerrain(),
      animation: false,
      timeline: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      navigationHelpButton: false,
      fullscreenButton: false,
      infoBox: false,
      selectionIndicator: false,
    });
    viewerRef.current = viewer;
    if (import.meta.env.DEV) Object.assign(window, { __hadaq: { viewer, districtRef } }); // for console debugging
    viewer.scene.globe.enableLighting = true; // sun follows the clock
    viewer.shadows = true;
    let disposed = false;
    let highlighted: { feature: Cesium3DTileFeature; color: Color } | null = null;
    let outline: Entity | null = null;
    let osmTiles: Awaited<ReturnType<typeof createOsmBuildingsAsync>> | null = null;

    // Grey OSM boxes stay for the rest of Diriyah; inside At-Turaif our own district model replaces them.
    const styleOsm = () => {
      if (!osmTiles) return;
      const conditions = [];
      if (districtRef.current) conditions.push(outsideDistrictCondition(districtRef.current));
      else if (FOCUS_MODEL_URL && FOCUS_OSM_ID) conditions.push("${elementId} !== " + FOCUS_OSM_ID);
      osmTiles.style = new Cesium3DTileStyle({ color: MUD, show: conditions.length ? conditions.join(" && ") : "true" });
    };

    // The normal map: globe with terrain and imagery, grey OSM boxes, and our district model where it exists.
    const loadNormalBase = () => {
      createOsmBuildingsAsync()
        .then((osm) => {
          if (disposed) return;
          viewer.scene.primitives.add(osm);
          osmTiles = osm;
          styleOsm();
        })
        .catch((err) => console.error("OSM buildings failed. Set VITE_CESIUM_ION_TOKEN in apps/web/.env.local", err));
      if (DISTRICT_URL) loadDistrictModel();
    };

    // Google Photorealistic 3D Tiles: a real photo-based mesh of the whole site (through Cesium ion, needs the ion token).
    // It replaces the globe surface, the OSM boxes and our district model. It is one continuous mesh, so clicks are
    // matched by location instead (see the click handler). If it fails, fall back to the normal map and say why.
    let googleActive = false;
    const startGoogle = () => {
      if (disposed) return; // the first of React's two dev-mode mounts never starts a request
      createGooglePhotorealistic3DTileset({ onlyUsingWithGoogleGeocoder: true })
        .then((tiles) => {
          if (disposed) return;
          viewer.scene.primitives.add(tiles);
          googleActive = true;
          googleTilesRef.current = tiles;
          setSurfaceTiles(tiles); // ground heights now come from Google's mesh
          const wall = propsRef.current.wall;
          if (focusModelInstance.current && wall) clipUnderFootprint(tiles, FOCUS_MODEL_URL, wall); // the model may have loaded first
          let failures = 0;
          tiles.tileFailed.addEventListener((e: { message?: string }) => {
            failures += 1;
            if (failures === 3) setNotice(`Google 3D tiles are failing to load (${e.message ?? "unknown error"}). Possibly the monthly quota.`);
          });
        })
        .catch((err: unknown) => {
          if (disposed) return;
          viewer.scene.globe.show = true;
          setSurfaceTiles(null); // heights fall back to Cesium terrain
          const reason = err instanceof Error ? err.message : String((err as { statusCode?: number })?.statusCode ?? err);
          console.error("Google 3D tiles failed", err);
          setNotice(
            `Google 3D did not load (${reason}), so the normal map is shown. Fix: in Cesium ion open Asset Depot, find "Google Photorealistic 3D Tiles", press "Add to my assets", then reload.`,
          );
          loadNormalBase();
        });
    };
    if (GOOGLE_3D) {
      viewer.scene.globe.show = false;
      setTimeout(startGoogle, 0);
    } else {
      loadNormalBase();
    }

    function loadDistrictModel() {
      loadDistrict(viewer, DISTRICT_URL)
        .then((district) => {
          if (disposed || !district) {
            if (!district) console.info(`No district model at ${DISTRICT_URL}; showing OSM boxes. See tools/turaif_model/README.md`);
            return;
          }
          districtRef.current = district;
          styleOsm();
          // The focus model may have loaded first: hide its district copy now.
          const focusId = FOCUS_OSM_ID;
          if (focusModelRef.current && focusId !== null) focusModelRef.current.then(() => setBuildingVisible(district, focusId, false));
        })
        .catch((err) => console.error("District model failed to load", err));
    }

    flyTo(viewer, "overview", null);

    const handler = new ScreenSpaceEventHandler(viewer.scene.canvas);
    handler.setInputAction((click: ScreenSpaceEventHandler.PositionedEvent) => {
      const picked = viewer.scene.pick(click.position);
      if (highlighted) {
        highlighted.feature.color = highlighted.color;
        highlighted = null;
      }
      if (outline) {
        viewer.entities.remove(outline);
        outline = null;
      }
      const wall = propsRef.current.wall;
      const clickedPoint = clickPoint(viewer, click.position);
      const zoneEntity = defined(picked) && picked.id instanceof Entity && String(picked.id.id).includes("-R");
      if (!zoneEntity && defined(clickedPoint) && wall && nearWall(clickedPoint, wall)) {
        propsRef.current.onSelect({ kind: "building", osmId: FOCUS_OSM_ID, name: "Focus building", isFocus: true, focusLocked: true });
        ensureFocusModel(viewer);
        return;
      }
      const building = pickedDistrictBuilding(districtRef.current, picked);
      if (building) {
        outline = outlineBuilding(viewer, districtRef.current!, building, HIGHLIGHT);
        const focusLocked = FOCUS_OSM_ID !== null && building.osm_id === FOCUS_OSM_ID;
        const isFocus = FOCUS_OSM_ID === null || focusLocked;
        const name = building.name_en || building.name || `Building ${building.osm_id}`;
        propsRef.current.onSelect({ kind: "building", osmId: building.osm_id, name, isFocus, focusLocked });
        if (isFocus) ensureFocusModel(viewer);
      } else if (googleActive && defined(picked) && !(picked.id instanceof Entity)) {
        // The Google mesh has no separate buildings: anything away from the focus wall is just "a building".
        propsRef.current.onSelect({ kind: "building", osmId: null, name: "At-Turaif building", isFocus: false });
      } else if (picked instanceof Cesium3DTileFeature) {
        const osmId = Number(picked.getProperty("elementId"));
        const name = String(picked.getProperty("name") ?? "Unnamed building");
        highlighted = { feature: picked, color: Color.clone(picked.color) };
        picked.color = HIGHLIGHT;
        const focusLocked = FOCUS_OSM_ID !== null && osmId === FOCUS_OSM_ID;
        const isFocus = FOCUS_OSM_ID === null || focusLocked; // no focus set yet = dev mode, any building opens the panel
        propsRef.current.onSelect({ kind: "building", osmId: Number.isFinite(osmId) ? osmId : null, name, isFocus, focusLocked });
      } else if (defined(picked) && picked.id instanceof Entity && String(picked.id.id).includes("-R")) {
        propsRef.current.onSelect({ kind: "zone", zoneId: String(picked.id.id) });
      }
    }, ScreenSpaceEventType.LEFT_CLICK);

    // Shift + click prints coordinates: use it to fill building lon/lat and the wall base_line in contracts/building_b01.json.
    handler.setInputAction(
      (click: ScreenSpaceEventHandler.PositionedEvent) => {
        const position = viewer.scene.pickPosition(click.position);
        if (!defined(position)) return;
        const carto = Cartographic.fromCartesian(position);
        const lon = CesiumMath.toDegrees(carto.longitude);
        const lat = CesiumMath.toDegrees(carto.latitude);
        console.info(`lon ${lon.toFixed(6)}, lat ${lat.toFixed(6)}, height ${carto.height.toFixed(1)} m`);
        propsRef.current.onCoordinate(lon, lat, carto.height);
      },
      ScreenSpaceEventType.LEFT_CLICK,
      KeyboardEventModifier.SHIFT,
    );

    return () => {
      disposed = true;
      // React dev mode mounts twice: forget everything tied to this viewer so the next one starts clean.
      focusModelRef.current = null;
      focusModelInstance.current = null;
      googleTilesRef.current = null;
      districtRef.current = null;
      handler.destroy();
      viewer.destroy();
      viewerRef.current = null;
    };
  }, []);

  // Wall zones (and our own model, if configured) once the registry is loaded.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || !props.wall) return;
    let cancelled = false;
    createWallZones(viewer, props.wall, props.buildingId).then((zones) => {
      if (cancelled) {
        zones.forEach((e) => viewer.entities.remove(e));
        return;
      }
      zonesRef.current = zones;
      colorZones(zones, propsRef.current.zoneColors);
      showZones(zones, propsRef.current.zonesVisible);
    });
    ensureFocusModel(viewer);
    createAttentionLayer(viewer, props.wall, props.buildingId).then((layer) => {
      if (cancelled) return;
      attentionRef.current = layer;
      layer.update(propsRef.current.attention);
    });
    return () => {
      cancelled = true;
      zonesRef.current.forEach((e) => viewer.entities.remove(e));
      zonesRef.current = new Map();
      attentionRef.current?.destroy();
      attentionRef.current = null;
    };
  }, [props.wall, props.buildingId]);

  useEffect(() => colorZones(zonesRef.current, props.zoneColors), [props.zoneColors]);
  useEffect(() => attentionRef.current?.update(props.attention), [props.attention]);
  useEffect(() => showZones(zonesRef.current, props.zonesVisible), [props.zonesVisible]);
  useEffect(() => highlightZone(zonesRef.current, props.selectedZone), [props.selectedZone]);

  useEffect(() => {
    if (!viewerRef.current || props.fly.nonce === 0) return;
    if (props.fly.target === "focus") ensureFocusModel(viewerRef.current);
    const model = focusModelInstance.current;
    if (props.fly.target === "focus" && model && props.wall) {
      // Frame our own model from in front of the facade, whatever the ground height is.
      viewerRef.current.camera.flyToBoundingSphere(model.boundingSphere, {
        offset: new HeadingPitchRange(CesiumMath.toRadians((props.wall.azimuth_deg + 180) % 360), CesiumMath.toRadians(-14), model.boundingSphere.radius * 2.2),
        duration: 2.5,
      });
      return;
    }
    flyTo(viewerRef.current, props.fly.target, props.wall);
  }, [props.fly.nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (viewerRef.current && props.clockIso) viewerRef.current.clock.currentTime = JulianDate.fromIso8601(props.clockIso);
  }, [props.clockIso]);

  return (
    <div className="site-map-wrap">
      <div ref={container} className="site-map" />
      {notice && (
        <div className="map-notice" onClick={() => setNotice(null)} title="Click to close">
          {notice}
        </div>
      )}
    </div>
  );
}
