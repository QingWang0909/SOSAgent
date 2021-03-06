#!/bin/sh

CONTROLLER_IP=128.110.154.235
CONTROLLER_REST_PORT=8080

curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/agent/add/json -X POST -d '{"ip-address":"10.0.0.2", "rest-ip-address":"130.127.133.74", "rest-port" : "8002" ,"control-port":"9998", "data-port":"9877", "feedback-port":"9998", "stats-port":"9999"}' | python -m json.tool
curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/agent/add/json -X POST -d '{"ip-address":"10.0.0.3", "rest-ip-address":"130.127.133.93", "rest-port" : "8002", "control-port":"9998", "data-port":"9877", "feedback-port":"9998", "stats-port":"9999"}' | python -m json.tool

curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/whitelist/add/json -X POST -d '{"server-ip-address":"10.0.0.4", "server-tcp-port":"5001", "client-ip-address":"10.0.0.1"}' | python -m json.tool

curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/config/json -X POST -d '{"parallel-connections":"25"}' | python -m json.tool
curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/config/json -X POST -d '{"queue-capacity":"4000"}' | python -m json.tool
curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/config/json -X POST -d '{"buffer-size":"1000"}' | python -m json.tool
curl http://$CONTROLLER_IP:$CONTROLLER_REST_PORT/wm/sos/config/json -X POST -d '{"idle-timeout":"2000"}' | python -m json.tool

exit 0