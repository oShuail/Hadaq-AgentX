// Sun position for a date and place (NOAA solar position equations, accurate to a fraction of a degree).

export interface SunPosition {
  altitude: number; // degrees above the horizon
  azimuth: number; // compass degrees, clockwise from north
}

export function sunPosition(date: Date, lat: number, lon: number): SunPosition {
  const rad = Math.PI / 180;
  const jd = date.getTime() / 86400000 + 2440587.5;
  const t = (jd - 2451545) / 36525;
  const l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360;
  const m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
  const e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
  const c =
    Math.sin(m * rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
    Math.sin(2 * m * rad) * (0.019993 - 0.000101 * t) +
    Math.sin(3 * m * rad) * 0.000289;
  const trueLong = l0 + c;
  const omega = 125.04 - 1934.136 * t;
  const lambda = trueLong - 0.00569 - 0.00478 * Math.sin(omega * rad);
  const eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
  const eps = eps0 + 0.00256 * Math.cos(omega * rad);
  const decl = Math.asin(Math.sin(eps * rad) * Math.sin(lambda * rad));
  const y = Math.tan((eps * rad) / 2) ** 2;
  const eqTime =
    (4 / rad) *
    (y * Math.sin(2 * l0 * rad) -
      2 * e * Math.sin(m * rad) +
      4 * e * y * Math.sin(m * rad) * Math.cos(2 * l0 * rad) -
      0.5 * y * y * Math.sin(4 * l0 * rad) -
      1.25 * e * e * Math.sin(2 * m * rad)); // minutes
  const utcMinutes = date.getUTCHours() * 60 + date.getUTCMinutes() + date.getUTCSeconds() / 60;
  let trueSolar = (utcMinutes + eqTime + 4 * lon) % 1440;
  if (trueSolar < 0) trueSolar += 1440;
  let hourAngle = trueSolar / 4 - 180;
  if (hourAngle < -180) hourAngle += 360;
  const latR = lat * rad;
  const cosZen = Math.sin(latR) * Math.sin(decl) + Math.cos(latR) * Math.cos(decl) * Math.cos(hourAngle * rad);
  const zenith = Math.acos(Math.max(-1, Math.min(1, cosZen)));
  const altitude = 90 - zenith / rad;
  const az = Math.atan2(
    Math.sin(hourAngle * rad),
    Math.cos(hourAngle * rad) * Math.sin(latR) - Math.tan(decl) * Math.cos(latR),
  );
  const azimuth = (az / rad + 180) % 360;
  return { altitude, azimuth };
}

/** Plain-words phase of the day for the clock ("Golden hour", "Night" ...). */
export function dayPhase(altitude: number, rising: boolean): string {
  if (altitude < -12) return "Night";
  if (altitude < -0.8) return rising ? "Dawn" : "Dusk";
  if (altitude < 8) return "Golden hour";
  return "Day";
}
