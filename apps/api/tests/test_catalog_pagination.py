import asyncio
from copy import deepcopy
from datetime import date,timedelta
import pytest
from app.config import Settings
from app.service import CampService
from app.providers import GoCampingAdapter,ProviderFailure
from app.errors import DomainError
from app.models import TripInput

def run(value):return asyncio.run(value)

def test_keyset_pages_have_no_duplicates_and_finish(tmp_path):
    service=CampService(Settings(db_path=tmp_path/'pages.sqlite3'))
    first=run(service.search(page_size=2));second=run(service.search(cursor=first['nextCursor'],page_size=2));third=run(service.search(cursor=second['nextCursor'],page_size=2))
    ids=[s['id'] for p in (first,second,third) for s in p['items']]
    assert len(ids)==len(set(ids))==first['total']==6
    assert not third['hasMore'] and third['nextCursor'] is None
    assert first['catalogGeneration']==second['catalogGeneration']==third['catalogGeneration']

def test_filter_and_page_size_are_bound_to_cursor(tmp_path):
    service=CampService(Settings(db_path=tmp_path/'binding.sqlite3'))
    first=run(service.search(page_size=1))
    for query in [{'region':'강원','page_size':1},{'page_size':2},{'facilities':'shower','page_size':1}]:
        with pytest.raises(DomainError) as error:run(service.search(cursor=first['nextCursor'],**query))
        assert error.value.status==422

def test_generation_change_requires_restart(tmp_path):
    service=CampService(Settings(db_path=tmp_path/'generation.sqlite3'))
    first=run(service.search(page_size=1));sites=service.repo.read()['catalog'];sites[0]['source']['updatedAt']='2099-01-01T00:00:00+00:00'
    service.repo.store_catalog(sites,service.providers.catalog_status,service.cache_variant)
    with pytest.raises(DomainError) as error:run(service.search(cursor=first['nextCursor'],page_size=1))
    assert error.value.status==409

def live_service(tmp_path,count=120):
    service=CampService(Settings(mode='live',gocamping_key='non-secret-test-key',db_path=tmp_path/'live-pages.sqlite3'))
    sites=[GoCampingAdapter.normalize({'contentId':str(n).zfill(3),'facltNm':f'장소{n}','doNm':'후속지역' if n>=100 else '앞지역','mapX':'127.0','mapY':'37.5'}) for n in range(count)]
    service.providers.catalog_status.update(status='available',mode='live')
    service.repo.store_catalog(sites,service.providers.catalog_status,service.cache_variant)
    return service

def test_filter_is_applied_before_paging_including_later_sites(tmp_path):
    service=live_service(tmp_path)
    page=run(service.search(region='후속지역',page_size=7))
    assert page['total']==20 and page['sourceTotal']==120 and len(page['items'])==7
    assert all(s['region']=='후속지역' and int(s['id'].split('-')[1])>=100 for s in page['items'])
    assert run(service.search(car_camping=False))['total']==0
    assert run(service.search())['total']==120

def test_later_page_failure_keeps_complete_published_catalog(tmp_path,monkeypatch):
    service=live_service(tmp_path,201)
    before=service.repo.read()['catalog'];first=run(service.search(page_size=20))
    async def fetch_page(self,number,size):
        if number==2:raise ProviderFailure('공급자 HTTP 503')
        rows=before[(number-1)*100:number*100]
        return {'items':deepcopy(rows),'sourceTotal':201,'providerRows':len(rows)}
    monkeypatch.setattr(GoCampingAdapter,'fetch_page',fetch_page)
    run(service._catalog(force=True))
    after=service.repo.read()['catalog']
    assert [s['id'] for s in before]==[s['id'] for s in after]
    second=run(service.search(cursor=first['nextCursor'],page_size=20))
    assert second['catalogGeneration']==first['catalogGeneration'] and len(second['items'])==20

def test_saved_trip_from_later_page_survives_service_restart(tmp_path):
    service=live_service(tmp_path)
    checkin=date.today()+timedelta(days=1)
    run(service.save_trip(TripInput(campsiteId='gocamping-119',checkIn=checkin,checkOut=checkin+timedelta(days=1),guests=2)))
    restarted=CampService(service.settings)
    boot=run(restarted.bootstrap())
    assert len(boot['campsites'])==20 and all(s['id']!='gocamping-119' for s in boot['campsites'])
    assert boot['tripCampsite']['id']=='gocamping-119' and boot['trip']['campsiteId']=='gocamping-119'

def test_background_snapshot_deduplicates_upstream_pages(monkeypatch):
    adapter=GoCampingAdapter('non-secret-test-key')
    async def fetch_page(number,size):
        ids=range(100) if number==1 else range(99,199) if number==2 else [199]
        rows=[GoCampingAdapter.normalize({'contentId':str(i)}) for i in ids]
        return {'items':rows,'sourceTotal':201,'providerRows':len(rows)}
    monkeypatch.setattr(adapter,'fetch_page',fetch_page)
    sites,total,requests=run(adapter.fetch_snapshot())
    assert total==201 and requests==3 and len(sites)==len({s['id'] for s in sites})==200

def test_failed_background_snapshot_can_retry_and_publish_atomically(tmp_path,monkeypatch):
    service=live_service(tmp_path,201);original=service.repo.read()['catalog'];fault={'enabled':True}
    async def fetch_page(self,number,size):
        if number==2 and fault['enabled']:raise ProviderFailure('공급자 HTTP 503')
        rows=deepcopy(original[(number-1)*100:number*100])
        for row in rows:row['name']='갱신된 '+row['name']
        return {'items':rows,'sourceTotal':201,'providerRows':len(rows)}
    monkeypatch.setattr(GoCampingAdapter,'fetch_page',fetch_page)
    run(service._catalog(force=True))
    assert service.repo.read()['catalog'][0]['name']==original[0]['name']
    fault['enabled']=False
    run(service._catalog(force=True))
    published=service.repo.read()['catalog']
    assert len(published)==201 and all(s['name'].startswith('갱신된 ') for s in published)
