# Phone MCP relay

A tiny reverse HTTP relay (Python 3, standard library only) for the app's **Your own server (relay)** option. It gives your
phone a fixed public URL on a server you control:

```
MCP client --HTTPS--> your server: /mcp/<secret> --> relay.py <--long-poll-- phone app --> local MCP server
```

The phone connects out, so it works on mobile data and behind NAT. TLS ends on your server: use one you control.

## Set up
1. Pick a long random relay token (it is different from the connector secret):
   `python3 -c "import secrets; print(secrets.token_urlsafe(32))"`
2. Run `relay.py` as a service on `127.0.0.1:18090`, for example with systemd:

   ```ini
   [Service]
   User=relay
   Environment=RELAY_TOKEN=<your relay token>
   Environment=RELAY_PORT=18090
   ExecStart=/usr/bin/python3 -I /opt/phone-relay/relay.py
   Restart=always
   ```

3. Put a TLS reverse proxy in front of it and send **`/mcp/*` and `/relay/*`** to `127.0.0.1:18090`. Caddy:

   ```
   handle /mcp/*   { reverse_proxy 127.0.0.1:18090 { flush_interval -1 } }
   handle /relay/* { reverse_proxy 127.0.0.1:18090 { flush_interval -1 } }
   ```

4. In the app choose **Your own server (relay)**, enter the server address (for example `203.0.113.7` or `relay.example.org`) and the relay
   token, then **Start and get URL**. The connector URL is `https://<server>/mcp/<secret>`.

## Behaviour
- Phone not connected: `503`. Phone does not answer within `JOB_TIMEOUT` (170 s, long enough for an approval tap): `504`.
- A wrong secret gets a bare `404` from the relay without waking the phone (it only knows `sha256(secret)`).
- Limits: 2 MB request body, 16 MB phone answer, 16 requests in flight. Secrets and tokens are never logged.
- Environment: `RELAY_TOKEN` (required, at least 32 characters), `RELAY_HOST`, `RELAY_PORT`, `POLL_SECONDS`, `JOB_TIMEOUT`, `PHONE_ALIVE`.

## Protocol
```
POST /relay/<token>/poll        {"h": "<sha256 hex of the connector secret>", "v": 1}
  200 {"id","method","path","headers","body_b64"}   a request for the phone to replay locally
  204                                                nothing within POLL_SECONDS: poll again
POST /relay/<token>/reply/<id>  {"status": 200, "headers": {...}, "body_b64": "..."}
```

Run the tests with `python3 relay/test_relay.py`.
