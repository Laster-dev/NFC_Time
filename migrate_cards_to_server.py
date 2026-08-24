import urllib.request
import json
import sys

cards_map = {
    "04:ED:A0:6A:8E:61:80": "01",
    "04:13:F3:6A:8E:61:81": "02",
    "04:55:17:6B:8E:61:80": "3",
    "04:F0:E6:6A:8E:61:80": "04",
    "04:E2:15:6A:8E:61:80": "05",
    "04:8A:95:6A:8E:61:81": "06",
    "04:A9:91:6A:8E:61:80": "07",
    "04:90:B8:6A:8E:61:80": "08",
    "04:09:FA:6A:8E:61:80": "09",
    "04:96:7F:6A:8E:61:80": "10",
    "04:F6:AF:6A:8E:61:80": "11",
    "04:AB:FA:6A:8E:61:80": "12",
    "04:87:41:6A:8E:61:80": "13",
    "04:1E:9A:6A:8E:61:80": "14",
    "04:8C:83:6A:8E:61:80": "15",
    "04:3C:5E:6A:8E:61:80": "16",
    "04:B5:27:6A:8E:61:80": "17",
    "04:B0:5F:6A:8E:61:80": "18",
    "04:A7:A6:6A:8E:61:80": "19",
    "04:BB:24:6A:8E:61:80": "20",
    "04:C7:0D:6A:8E:61:80": "？？",
    "64:53:0C:41": "卡片_64:53:0C:41"
}

server_urls = ["http://43.140.218.3:5000", "http://localhost:5000"]

if len(sys.argv) > 1:
    server_urls = [sys.argv[1]]

for base_url in server_urls:
    print(f"=== 正在连接并同步至服务器: {base_url} ===")
    try:
        req = urllib.request.Request(f"{base_url}/api/system/info", headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=4) as resp:
            info = json.loads(resp.read().decode("utf-8"))
            print(f"  [在线] App: {info.get('appName', '')} (Version: {info.get('version', '')})")
    except Exception as e:
        print(f"  [跳过] 无法连接到 {base_url} ({e})\n")
        continue

    success_count = 0
    for uid, name in cards_map.items():
        try:
            payload = json.dumps({"cardId": uid, "cardName": name}).encode("utf-8")
            req = urllib.request.Request(
                f"{base_url}/api/cards/swipe",
                data=payload,
                headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"},
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    cname = data.get('name')
                    print(f"  [SUCCESS] UID: {uid} -> Name: {cname}")
                    success_count += 1
                else:
                    print(f"  [FAILED] UID: {uid} HTTP {resp.status}")
        except Exception as e:
            print(f"  [ERROR] UID: {uid} -> {e}")
    print(f"=== {base_url} Sync Done: {success_count}/{len(cards_map)} cards ===\n")
