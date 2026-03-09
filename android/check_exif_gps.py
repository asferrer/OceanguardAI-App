"""Check EXIF GPS data in images. Usage: python check_exif_gps.py <image_or_folder>"""
import sys, os
from PIL import Image
from PIL.ExifTags import TAGS, GPSTAGS

def get_gps_info(path: str) -> dict | None:
    try:
        img = Image.open(path)
        exif = img._getexif()
        if not exif:
            return None
        gps_info = {}
        for tag_id, value in exif.items():
            if TAGS.get(tag_id) == "GPSInfo":
                for gps_tag_id, gps_value in value.items():
                    gps_info[GPSTAGS.get(gps_tag_id, gps_tag_id)] = gps_value
                return gps_info if gps_info else None
    except Exception as e:
        return None
    return None

def dms_to_decimal(dms, ref):
    degrees, minutes, seconds = [float(x) for x in dms]
    decimal = degrees + minutes / 60 + seconds / 3600
    if ref in ("S", "W"):
        decimal = -decimal
    return decimal

def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "."
    files = []
    if os.path.isdir(target):
        for f in sorted(os.listdir(target)):
            if f.lower().endswith((".jpg", ".jpeg", ".png", ".tiff", ".heic")):
                files.append(os.path.join(target, f))
    else:
        files = [target]

    if not files:
        print("No image files found.")
        return

    print(f"{'File':<40} {'GPS?':<6} {'Latitude':<14} {'Longitude':<14}")
    print("-" * 76)

    found, total = 0, 0
    for f in files:
        total += 1
        name = os.path.basename(f)[:39]
        gps = get_gps_info(f)
        if gps and "GPSLatitude" in gps and "GPSLongitude" in gps:
            lat = dms_to_decimal(gps["GPSLatitude"], gps.get("GPSLatitudeRef", "N"))
            lon = dms_to_decimal(gps["GPSLongitude"], gps.get("GPSLongitudeRef", "E"))
            print(f"{name:<40} {'YES':<6} {lat:<14.6f} {lon:<14.6f}")
            found += 1
        else:
            print(f"{name:<40} {'NO':<6} {'—':<14} {'—':<14}")

    print(f"\n{found}/{total} images have GPS data.")

if __name__ == "__main__":
    main()
