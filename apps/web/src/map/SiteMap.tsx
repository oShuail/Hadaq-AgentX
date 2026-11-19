// Diriyah site map (shell: Turky; wall zones and focus model: Omar).
import {
  Cartesian3,
  Cartographic,
  Cesium3DTileFeature,
  Cesium3DTileStyle,
  Color,
  Entity,
  Ion,
  JulianDate,
  KeyboardEventModifier,
  Math as CesiumMath,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  Terrain,
  Viewer,
  createOsmBuildingsAsync,
  defined,
} from "cesium";
import "cesium/Build/Cesium/Widgets/widgets.css";
import { useEffect, useRef } from "react";

import { CESIUM_ION_TOKEN, FOCUS_MODEL_URL, FOCUS_OSM_ID, SITE } from "../config";
import { offsetLonLat } from "../twin/geo";
import { placeFocusModel } from "../twin/focusModel";
import { type AttentionItem, type AttentionLayer, colorZones, createAttentionLayer, createWallZones, highlightZone, showZones } from "../twin/WallZones";
import type { WallSpec } from "../types/contracts";

export type Selection =
  | { kind: "building"; osmId: number | null; name: string; isFocus: boolean }
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
    viewer.scene.globe.enableLighting = true; // sun follows the clock
    viewer.shadows = true;
    let disposed = false;
    let highlighted: { feature: Cesium3DTileFeature; color: Color } | null = null;

    createOsmBuildingsAsync()
      .then((osm) => {
        if (disposed) return;
        viewer.scene.primitives.add(osm);
        // When our own model of the focus building exists, hide the OSM copy of that one building.
        const hideFocus = FOCUS_MODEL_URL && FOCUS_OSM_ID ? "${elementId} !== " + FOCUS_OSM_ID : "true";
        osm.style = new Cesium3DTileStyle({ color: MUD, show: hideFocus });
      })
      .catch((err) => console.error("OSM buildings failed. Set VITE_CESIUM_ION_TOKEN in apps/web/.env.local", err));

    flyTo(viewer, "overview", null);

    const handler = new ScreenSpaceEventHandler(viewer.scene.canvas);
    handler.setInputAction((click: ScreenSpaceEventHandler.PositionedEvent) => {
      const picked = viewer.scene.pick(click.position);
      if (highlighted) {
        highlighted.feature.color = highlighted.color;
        highlighted = null;
      }
      if (picked instanceof Cesium3DTileFeature) {
        const osmId = Number(picked.getProperty("elementId"));
        const name = String(picked.getProperty("name") ?? "Unnamed building");
        highlighted = { feature: picked, color: Color.clone(picked.color) };
        picked.color = HIGHLIGHT;
        const isFocus = FOCUS_OSM_ID === null || osmId === FOCUS_OSM_ID; // no focus set yet = dev mode, any building opens the panel
        propsRef.current.onSelect({ kind: "building", osmId: Number.isFinite(osmId) ? osmId : null, name, isFocus });
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
    createAttentionLayer(viewer, props.wall, props.buildingId).then((layer) => {
      if (cancelled) return;
      attentionRef.current = layer;
      layer.update(propsRef.current.attention);
    });
    if (FOCUS_MODEL_URL) {
      const { start, end } = props.wall.base_line;
      placeFocusModel(viewer, FOCUS_MODEL_URL, (start.lon + end.lon) / 2, (start.lat + end.lat) / 2, props.wall.azimuth_deg).catch((err) =>
        console.error("Focus model failed to load", err),
      );
    }
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
    if (viewerRef.current && props.fly.nonce > 0) flyTo(viewerRef.current, props.fly.target, props.wall);
  }, [props.fly.nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (viewerRef.current && props.clockIso) viewerRef.current.clock.currentTime = JulianDate.fromIso8601(props.clockIso);
  }, [props.clockIso]);

  return <div ref={container} className="site-map" />;
}
