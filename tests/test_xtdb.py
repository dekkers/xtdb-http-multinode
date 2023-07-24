import random
import string
import time

import pytest
import requests


@pytest.fixture
def xtdb_node():
    node_name = ''.join(random.sample(string.ascii_lowercase, 10))

    json_headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    response = requests.post('http://127.0.0.1:3000/_xtdb/create-node', headers=json_headers, timeout=5, data=f'{{"node": "{node_name}"}}')
    assert response.status_code == 200
    assert response.content == b'{"created":true}'

    yield node_name

    response = requests.post('http://127.0.0.1:3000/_xtdb/delete-node', headers=json_headers, timeout=5, data=f'{{"node": "{node_name}"}}')
    assert response.status_code == 200
    assert response.content == b'{"deleted":true}'


def test_submit_tx(xtdb_node):
    json_headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}

    data = '{"tx-ops": [["put", {"xt/id": "ivan", "name": "Ivan", "last-name": "Petrov"}],["put", {"xt/id": "boris", "name": "Boris", "last-name": "Petrov"}]]}'

    response = requests.post(f'http://127.0.0.1:3000/_xtdb/{xtdb_node}/submit-tx', headers=json_headers, timeout=5, data=data)
    assert response.status_code == 202

    time.sleep(0.2)

    response = requests.get(f'http://127.0.0.1:3000/_xtdb/{xtdb_node}/entity?eid=boris', headers=json_headers, timeout=5, data=data)
    assert response.status_code == 200
    entity = response.json()

    assert entity['xt/id'] == 'boris'
    assert entity['last-name'] == 'Petrov'


# def test_malformed(xtdb_node):
#     edn_headers = {'Content-Type': 'application/edn', 'Accept': 'application/adn'}

#     data = '{:tx-ops [[]]}'
#     response = requests.post(f'http://127.0.0.1:3000/_xtdb/{xtdb_node}/submit-tx', headers=edn_headers, timeout=5, data=data)
#     assert response.status_code == 200
