"""Refresh app/src/main/assets/stores.json from OpenStreetMap (Overpass API).
Runs on the PC only (the app has no INTERNET permission).
Usage: python tools/fetch_stores.py"""
import json, os, re, urllib.request, urllib.parse

# Vilnius city bounding box (south, west, north, east)
BBOX = "54.56,25.02,54.83,25.48"
SHOPS = "supermarket|convenience|chemist|department_store|doityourself|hardware|clothes|shoes|electronics|sports|beauty|cosmetics|pet|furniture|books|variety_store|mall|bakery"
QUERY = f"""[out:json][timeout:120];
(
  nwr["shop"~"^({SHOPS})$"]["brand"]({BBOX});
  nwr["amenity"="pharmacy"]["brand"]({BBOX});
  nwr["amenity"="fuel"]["brand"]({BBOX});
);
out center tags;"""

# Try the main server first, then public mirrors.
MIRRORS = ["https://overpass-api.de/api/interpreter",
           "https://overpass.kumi.systems/api/interpreter",
           "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
           "https://overpass.private.coffee/api/interpreter"]
data = None
for url in MIRRORS:
    try:
        req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": QUERY}).encode(),
                                     headers={"User-Agent": "personal-launcher-store-refresh/1.0"})
        data = json.load(urllib.request.urlopen(req, timeout=180)); print("from", url); break
    except Exception as e:
        print("failed", url, e)
if data is None: raise SystemExit("all Overpass mirrors failed")
def brand(b):
    """Merge store-format variants into one brand: 'Maxima XX' -> 'Maxima', 'Iki' -> 'IKI'."""
    b = re.sub(r"\s+(X{1,3}|XL)$", "", b.strip())
    return {"iki": "IKI"}.get(b.lower(), b)

out = []
for el in data["elements"]:
    lat = el.get("lat") or el.get("center", {}).get("lat")
    lon = el.get("lon") or el.get("center", {}).get("lon")
    t = el.get("tags", {})
    if lat and lon and t.get("brand"):
        out.append({"b": brand(t["brand"]), "n": t.get("name", t["brand"]), "lat": round(lat, 6), "lon": round(lon, 6)})
dst = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "stores.json")
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
print(len(out), "stores,", len({s['b'] for s in out}), "brands ->", os.path.abspath(dst))
