import socket
from zeroconf import IPVersion, Info, Zeroconf

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip

def start_discovery_service(port=8000):
    local_ip = get_local_ip()
    packed_ip = socket.inet_aton(local_ip)
    
    info = Info(
        "_friday_hub._tcp.local.",
        "FRIDAY Compute Hub._friday_hub._tcp.local.",
        addresses=[packed_ip],
        port=port,
        properties={},
    )
    
    zeroconf = Zeroconf(ip_version=IPVersion.All)
    print(f"[Discovery] Advertising FRIDAY Hub at {local_ip}:{port}")
    zeroconf.register_service(info)
    return zeroconf, info