"""Illustrative fixtures. These are never represented as live inventory or real places."""
from datetime import date, datetime, timedelta, timezone

def now():
    return datetime.now(timezone.utc).isoformat()

def trip_dates():
    today = date.today()
    start = today + timedelta(days=((5 - today.weekday()) % 7 or 7))
    return start.isoformat(), (start + timedelta(days=1)).isoformat()

def campsites():
    stamp = now()
    rows = [
        ("solbaram", "솔바람 오토캠핑장", "강원", "강원 양양군 손양면 바다로 128 (가상 시연 주소)",38.02,128.71,55000,["shower","electricity","pets","toilet","water"],True,True,True,"소나무 향과 파도 소리 사이, 바다까지 걸어서 5분인 차박 공간입니다.","photo-1478131143081-fd1d2b8c4a8f", "available",8),
        ("byeolbit", "별빛숲 캠핑 빌리지", "강원", "강원 홍천군 숲속길 42 (가상 시연 주소)",37.69,127.89,48000,["shower","electricity","toilet","water"],True,False,True,"깊은 숲의 여유와 별이 쏟아지는 밤을 만나는 조용한 캠핑장입니다.","photo-1504851149312-7a075b496cc7","limited",2),
        ("morning", "아침고요 차박 가든", "경기", "경기 가평군 아침길 17 (가상 시연 주소)",37.74,127.42,60000,["shower","electricity","pets","toilet"],True,False,False,"잔디 마당과 산책로가 이어지는 가족 캠핑 정원입니다.","photo-1504280390367-361c6d9f38f4","available",5),
        ("lake", "청풍호 레이크 캠프", "충북", "충북 제천시 청풍면 호숫길 9 (가상 시연 주소)",37.00,128.17,52000,["shower","electricity","toilet","water"],True,True,True,"호수의 물안개를 바라보며 아침 커피를 즐길 수 있습니다.","photo-1523987355523-c7b5b0dd90a7","unknown",None),
        ("island", "노을바다 캠핑 스테이", "제주", "제주 제주시 노을해안길 75 (가상 시연 주소)",33.37,126.18,65000,["shower","toilet","water"],False,False,False,"제주의 바람과 노을을 느끼는 텐트 전용 공간입니다.","photo-1464822759023-fed622ff2c3b","unavailable",0),
        ("valley", "청량계곡 포레스트", "경북", "경북 봉화군 계곡길 31 (가상 시연 주소)",36.84,128.88,45000,["shower","electricity","pets","toilet","water"],True,False,True,"맑은 계곡을 곁에 둔 나무 그늘 아래에서 쉬어 가세요.","photo-1455763916899-e8b50eca9967","available",12),
    ]
    return [dict(id=r[0],name=r[1],region=r[2],address=r[3],latitude=r[4],longitude=r[5],nightlyPrice=r[6],facilities=r[7],carCampingAllowed=r[8],evChargingAllowed=r[9],delivery={"acceptsParcels":r[10],"location":"관리동 택배 보관소" if r[10] else "수령 불가","instructions":"도착일 09:00–18:00 관리동 수령 · 수령인과 사이트 번호를 적어 주세요." if r[10] else "이 캠핑장은 방문객 택배를 보관하지 않습니다."},description=r[11],imageUrl=f"https://images.unsplash.com/{r[12]}?auto=format&fit=crop&w=1100&q=85",checkInTime="14:00",checkOutTime="11:00",rules=["매너타임 22:00–07:00", "화로는 지정 구역에서만 이용해 주세요.", "차박 허용 여부는 운영 규칙을 다시 확인해 주세요."],availability={"status":r[13],"remaining":r[14],"coverage":"시연용 전체 숙박 기간 가용성","checkedAt":stamp,"provider":"Camp Room 데모 예약 어댑터","demo":True},source={"name":"Camp Room 가상 캠핑장 · 데모","updatedAt":stamp,"mode":"demo"}) for r in rows]

def products():
    rows=[
      ("sleeping-bag","포근한 3계절 침낭",79000,20,"sleep","밤 기온이 낮은 캠핑을 위해 보온 침낭을 준비하세요.","photo-1523987355523-c7b5b0dd90a7"),
      ("camp-lantern","무드 포터블 랜턴",39000,30,"light","매너타임에도 주변을 방해하지 않는 은은한 빛입니다.","photo-1504851149312-7a075b496cc7"),
      ("coffee-kit","아웃도어 커피 키트",45000,12,"kitchen","호숫가와 숲에서 간편하게 아침 커피를 즐겨 보세요.","photo-1442512595331-e89e73853f31"),
      ("storage-box","접이식 캠핑 수납함",32000,18,"storage","트렁크와 차박 공간을 함께 정리할 수 있습니다.","photo-1496545672447-f699b503d270"),
      ("rain-tarp","컴팩트 방수 타프",89000,10,"shelter","소나기에도 캠핑 장비를 보관할 여유 공간을 만드세요.","photo-1478131143081-fd1d2b8c4a8f"),
      ("camp-mat","차박 릴랙스 매트",59000,15,"sleep","지원되는 좌석 배치를 확인한 뒤 편안한 휴식 공간을 준비하세요.","photo-1504280390367-361c6d9f38f4"),
    ]
    return [dict(id=r[0],name=r[1],price=r[2],stock=r[3],category=r[4],recommendationReason=r[5],imageUrl=f"https://images.unsplash.com/{r[6]}?auto=format&fit=crop&w=600&q=80") for r in rows]

def initial_state():
    checkin,checkout=trip_dates()
    return {"trip":{"campsiteId":"solbaram","checkIn":checkin,"checkOut":checkout,"guests":2,"navigationStarted":False,"updatedAt":now()},"vehicle":{"mode":"demo","motionState":"parked","soc":78,"cabinTemperature":21.5,"targetTemperature":22,"front":{"readingLight":False,"ambientBrightness":35,"blindLevel":0},"rear":{"readingLight":False,"ambientBrightness":25,"blindLevel":0},"doorsClosed":True,"windowsClosed":True,"lastSceneId":None,"updatedAt":now()},"cart":{},"orders":[],"idempotency":{},"inventory":{p["id"]:p["stock"] for p in products()}}
