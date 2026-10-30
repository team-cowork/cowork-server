"""Overview node positions and orthogonal connection routes."""

import json

from diagrams import Diagram
from graphviz import Source
from graphviz.quoting import quote


# Graphviz points, with the origin at the bottom left.
POSITIONS = {
    "Web / Mobile 클라이언트": (80, 1543),
    "TLS 리버스 프록시": (292, 1543),
    "cowork-gateway": (1171, 1280),
    "cowork-user": (643, 1043),
    "cowork-project": (1096, 1043),
    "cowork-channel": (1247, 1043),
    "cowork-team": (1549, 1043),
    "cowork-authorization": (1700, 1043),
    "user-db": (643, 806),
    "user-storage": (794, 806),
    "cowork-github": (945, 806),
    "project-db": (1096, 806),
    "channel-db": (1247, 806),
    "team-db": (1398, 806),
    "team-storage": (1549, 806),
    "authorization-db": (1700, 806),
    "cowork-voice": (654, 583),
    "cowork-roadmap": (805, 583),
    "cowork-chat": (956, 583),
    "cowork-notification": (1409, 583),
    "cowork-preference": (1560, 583),
    "voice-db": (654, 360),
    "roadmap-db": (805, 360),
    "chat-db": (956, 360),
    "chat-storage": (1107, 360),
    "chat-search": (1258, 360),
    "notification.db": (1409, 360),
    "preference-db": (1560, 360),
    "LiveKit SFU": (352, 360),
    "Redis": (503, 360),
    "Kafka": (503, 138),
    "Consul": (1876, 1043),
    "Nomad": (1876, 806),
    "Vault": (1876, 583),
    "계정 공유 OAuth (선택)": (2106, 806),
    "DataGSM": (2257, 806),
    "GitHub": (2106, 583),
    "Discord Webhook": (2106, 360),
    "Firebase FCM": (2257, 360),
}


class OverviewDiagram(Diagram):
    def render(self):
        data = json.loads(self.dot.pipe(format="json0", quiet=True))
        objects = {obj["_gvid"]: obj for obj in data["objects"]}
        names, boxes = {}, {}
        for key, obj in objects.items():
            if "pos" not in obj:
                continue
            name = obj["label"].split("\n")[0]
            x, y = POSITIONS[name]
            width, height = float(obj["width"]) * 72, float(obj["height"]) * 72
            names[name] = obj["name"]
            boxes[key] = (x - width / 2, y - height / 2, x + width / 2, y + height / 2)
            self.dot.node(obj["name"], pos=f"{x},{y}")

        def route(tail, head, points, label_at, label=None, reverse=False):
            # Repeated cubic controls render each segment as a straight line.
            px, py = points[-2]
            ex, ey = points[-1]
            distance = abs(ex - px) + abs(ey - py)
            points = [*points[:-1], (ex - 8 * (ex - px) / distance, ey - 8 * (ey - py) / distance)]
            arrows = f"e,{ex},{ey} "
            if reverse:
                sx, sy = points[0]
                nx, ny = points[1]
                distance = abs(nx - sx) + abs(ny - sy)
                points[0] = (sx + 8 * (nx - sx) / distance, sy + 8 * (ny - sy) / distance)
                arrows = f"s,{sx},{sy} " + arrows
            controls = [points[0]]
            for start, end in zip(points, points[1:]):
                controls.extend((start, end, end))
            path = arrows + " ".join(f"{x},{y}" for x, y in controls)
            attrs = {"pos": path, "xlp": f"{label_at[0]},{label_at[1]}"}
            if label is not None:
                attrs["xlabel"] = label
            self.dot.edge(names[tail], names[head], **attrs)

        route("cowork-voice", "LiveKit SFU",
              [(603.5, 583), (352, 583), (352, 443)], (480, 602), reverse=True)
        route("cowork-project", "cowork-github",
              [(1045.5, 1043), (1020, 1043), (1020, 900), (945, 900), (945, 874)],
              (967, 925), "internal API\n(Envoy)")
        route("cowork-github", "GitHub",
              [(945, 737), (945, 700), (2106, 700), (2106, 651)], (1480, 712))
        route("GitHub", "TLS 리버스 프록시",
              [(2156.5, 583), (2340, 583), (2340, 1470), (400, 1470), (400, 1543), (342.5, 1543)],
              (1990, 1482), "GitHub 서명 Webhook")
        route("DataGSM", "TLS 리버스 프록시",
              [(2307.5, 806), (2360, 806), (2360, 1494), (380, 1494), (380, 1525), (342.5, 1525)],
              (1990, 1508), "DataGSM 서명 Webhook")
        route("cowork-authorization", "DataGSM",
              [(1700, 975), (1700, 940), (2257, 940), (2257, 889)], (2170, 952))
        route("cowork-channel", "계정 공유 OAuth (선택)",
              [(1297.5, 1043), (1310, 1043), (1310, 920), (2106, 920), (2106, 903)], (1560, 932))
        route("cowork-notification", "Firebase FCM",
              [(1409, 515), (1409, 490), (2257, 490), (2257, 429)], (2190, 502))
        route("cowork-chat", "Discord Webhook",
              [(956, 515), (956, 470), (2106, 470), (2106, 429)], (1560, 456))
        route("cowork-gateway", "Consul",
              [(1221.5, 1300), (1790, 1300), (1790, 1043), (1825.5, 1043)], (1510, 1312))
        route("Nomad", "Vault", [(1876, 723), (1876, 651)], (1805, 689))
        route("Web / Mobile 클라이언트", "LiveKit SFU",
              [(29.5, 1543), (0, 1543), (0, 360), (301.5, 360)], (95, 1150),
              "WebRTC\n공개 TCP 38970 / UDP 35268\nVM TCP 7881 / UDP 7882")
        route("TLS 리버스 프록시", "cowork-gateway",
              [(292, 1475), (292, 1355), (1120.5, 1355)], (750, 1367))

        self.dot.graph_attr["concentrate"] = "false"
        source = self.dot.source

        # neato -n2 preserves supplied cluster bounds; compute children first.
        def bounds(key):
            if key in boxes:
                return boxes[key]
            obj = objects[key]
            children = [bounds(child) for child in (*obj["nodes"], *obj.get("subgraphs", []))]
            label_width = float(obj.get("lwidth", 0)) * 72
            label_height = float(obj.get("lheight", 0)) * 72
            left = min(box[0] for box in children) - 10
            bottom = min(box[1] for box in children) - 10
            right = max(box[2] for box in children) + 10
            top = max(box[3] for box in children) + max(30, label_height + 16)
            extra = max(0, label_width + 20 - (right - left)) / 2
            boxes[key] = (left - extra, bottom, right + extra, top)
            return boxes[key]

        for key, obj in objects.items():
            if not obj["name"].startswith("cluster_"):
                continue
            left, bottom, right, top = bounds(key)
            label_width = float(obj.get("lwidth", 0)) * 72
            label_height = float(obj.get("lheight", 0)) * 72
            bb = f"{left},{bottom},{right},{top}"
            lp = f"{left + label_width / 2 + 8},{top - label_height / 2 - 8}"
            declaration = f"subgraph {quote(obj['name'])} {{"
            source = source.replace(declaration, declaration + f'\n graph [bb="{bb}" lp="{lp}"];')

        Source(source, filename=self.filename, engine="neato").render(
            format=self.outformat, neato_no_op=2, view=self.show, quiet=True,
        )
