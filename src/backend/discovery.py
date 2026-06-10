import socket
from zeroconf import IPVersion, ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

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

async def start_discovery_service(port=8000):
    local_ip = get_local_ip()
    packed_ip = socket.inet_aton(local_ip)
    
    info = ServiceInfo(
        "_friday-hub._tcp.local.",
        "FRIDAY Compute Hub._friday-hub._tcp.local.",
        addresses=[packed_ip],
        port=port,
        properties={},
    )
    
    aiozc = AsyncZeroconf(ip_version=IPVersion.V4Only)
    print(f"[Discovery] Advertising FRIDAY Hub at {local_ip}:{port}")
    await aiozc.zeroconf.async_register_service(info)
    return aiozc, info