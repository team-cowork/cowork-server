from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.general import InternetAlt1, Users
from diagrams.aws.network import ElbApplicationLoadBalancer
from diagrams.custom import Custom
from diagrams.elastic.elasticsearch import Elasticsearch
from diagrams.firebase.grow import FCM
from diagrams.onprem.ci import GithubActions
from diagrams.onprem.container import Docker
from diagrams.onprem.database import MongoDB, MySQL, PostgreSQL
from diagrams.onprem.inmemory import Redis
from diagrams.onprem.logging import Loki
from diagrams.onprem.monitoring import Grafana, Prometheus
from diagrams.onprem.queue import Kafka
from diagrams.onprem.security import Vault
from diagrams.onprem.vcs import Github
from diagrams.saas.chat import Discord

HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "out"
SEAWEEDFS_ICON = str(HERE / "icons" / "seaweedfs.png")
LIVEKIT_ICON = str(HERE / "icons" / "livekit.png")
SQLITE_ICON = str(HERE / "icons" / "sqlite.png")
FONT = "Apple SD Gothic Neo"
GRAPH = {
    "fontname": FONT, "fontsize": "24", "pad": "0.8",
    "splines": "ortho", "nodesep": "0.7", "ranksep": "1.2", "newrank": "true", "concentrate": "true",
}
NODE = {"fontname": FONT, "fontsize": "13"}
EDGE = {"fontname": FONT, "fontsize": "12", "penwidth": "1.4", "arrowsize": "0.8"}

HTTP = "#2563eb"
EVENT = "#d97706"
DATA = "#475569"
EXTERNAL = "#7c3aed"
CONTROL = "#059669"
OBS = "#dc2626"

def SQLite(label):
    return Custom(label, SQLITE_ICON)


SERVICES = [
    ("authorization", MySQL, "10.0.0.140:8081"),
    ("user", MySQL, "10.0.0.144:8082"),
    ("channel", MySQL, "10.0.0.97:8083"),
    ("project", MySQL, "10.0.0.88:8084"),
    ("team", MySQL, "10.0.0.74:8085"),
    ("notification", SQLite, "10.0.0.131:8086"),
    ("chat", MongoDB, "10.0.0.174:8087"),
    ("roadmap", MySQL, "10.0.0.132:8088"),
    ("voice", MongoDB, "10.0.0.90:8089"),
    ("preference", PostgreSQL, "10.0.0.89:9001"),
    ("github", None, "10.0.0.150:8090"),
]
WITH_STORAGE = {"user", "team", "chat"}
REDIS_CLIENTS = {"user", "chat", "preference", "github"}


def edge(color, label="", style="solid", **attrs):
    return Edge(color=color, style=style, xlabel=label, **attrs)


def service(name, detail=""):
    return Docker(f"cowork-{name}\n{detail}".strip())


def platform(extend=None):
    nodes = {"clients": Users("Web / Mobile 클라이언트")}

    with Cluster("GSM SV Edge  (*.https.gsmsv.site)"):
        edge_proxy = ElbApplicationLoadBalancer("TLS 리버스 프록시")

    with Cluster("GSM SV 사설망  10.0.0.0/24") as network:
        with Cluster("10.0.0.127:8080"):
            gateway = service("gateway")

        with Cluster("Control Plane VMs"):
            with Cluster("10.0.0.169:8761"):
                config = service("config", "Config + Eureka")
            with Cluster("10.0.0.91:8200"):
                vault = Vault("Vault")

        apps = {}
        rows = []
        with Cluster("Service VMs  (VM 1대 = 서비스 + 전용 DB)") as service_vms:
            for row in (SERVICES[:5], SERVICES[5:]):
                tops, bottoms = [], []
                for name, engine, address in row:
                    with Cluster(address):
                        app = service(name)
                        if engine is None:
                            apps[name] = app
                            tops.append(app)
                            continue
                        database = engine(f"{name}-db")
                        app >> edge(DATA) >> database
                        if name in WITH_STORAGE:
                            app >> edge(DATA) >> Custom(f"{name}-storage", SEAWEEDFS_ICON)
                        if name == "chat":
                            app >> edge(DATA) >> Elasticsearch("chat-search")
                        apps[name] = app
                        tops.append(app)
                        bottoms.append(database)
                rows.append((tops, bottoms))

        with Cluster("Shared Infra VMs"):
            with Cluster("10.0.0.167:9094"):
                kafka = Kafka("Kafka")
            with Cluster("10.0.0.145:6379"):
                redis = Redis("Redis")
            with Cluster("10.0.0.172:7880"):
                livekit = Custom("LiveKit SFU", LIVEKIT_ICON)

        nodes.update(edge_proxy=edge_proxy, network=network, gateway=gateway, config=config, vault=vault,
                     apps=apps, service_vms=service_vms, kafka=kafka, redis=redis, livekit=livekit)
        if extend:
            extend(nodes)

    with Cluster("External SaaS"):
        datagsm = InternetAlt1("DataGSM OAuth2")
        fcm = FCM("Firebase FCM")
        discord = Discord("Discord Webhook")
        github = Github("GitHub")
    nodes.update(discord=discord, github=github)

    nodes["clients"] >> edge(HTTP, "HTTPS / WSS") >> edge_proxy >> edge(HTTP) >> gateway
    nodes["clients"] >> edge(HTTP, "WebRTC (TCP 7881 / UDP 7882)", "dashed") >> livekit

    gateway >> edge(CONTROL, "lb:// 디스커버리", "dashed") >> config
    config >> edge(CONTROL, "AppRole", "dashed") >> vault
    gateway >> edge(DATA, "Rate Limiter", "dotted") >> redis

    for upper, lower in zip(rows[0][1], rows[1][0]):
        upper >> Edge(style="invis") >> lower
    for database in rows[1][1]:
        database >> Edge(style="invis") >> kafka
    vault >> Edge(style="invis") >> kafka

    for name, app in apps.items():
        gateway >> edge(HTTP) >> app
        app >> edge(EVENT, style="dashed", penwidth="0.8") >> kafka
        if name in REDIS_CLIENTS:
            app >> edge(DATA, style="dotted", penwidth="0.8") >> redis

    apps["voice"] >> edge(HTTP, "Room API") >> livekit
    apps["project"] >> edge(HTTP, "internal API (Eureka)") >> apps["github"]
    apps["github"] >> edge(EXTERNAL, "GitHub App API") >> github
    github >> edge(EXTERNAL, "서명 Webhook", "dashed", constraint="false") >> edge_proxy
    apps["authorization"] >> edge(EXTERNAL, "OAuth2") >> datagsm
    apps["notification"] >> edge(EXTERNAL, "푸시") >> fcm
    apps["chat"] >> edge(EXTERNAL, "업무 알림") >> discord
    return nodes


def overview():
    with Diagram("cowork 클라우드 아키텍처", filename=str(OUTPUT / "overview"), show=False,
                 direction="TB", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
        platform()


def full():
    observability = {}

    def add_monitoring(nodes):
        with Cluster("10.0.0.137  Monitoring VM"):
            observability.update(
                grafana=Grafana("Grafana"),
                prometheus=Prometheus("Prometheus"),
                loki=Loki("Loki"),
                alertmanager=Prometheus("Alertmanager"),
                blackbox=Prometheus("Blackbox\nexporter"),
                exporters=Prometheus("Redis / Kafka\nexporter"),
            )
        with Cluster("모든 VM 공통 에이전트"):
            observability.update(
                node_agent=Prometheus("node-exporter"),
                log_agent=Docker("Grafana Alloy\nlog-agent"),
                db_exporter=Prometheus("DB exporter\n(서비스 VM)"),
            )

    with Diagram("cowork 전체 인프라", filename=str(OUTPUT / "full"), show=False,
                 direction="TB", graph_attr={**GRAPH, "compound": "true"}, node_attr=NODE, edge_attr=EDGE):
        with Cluster("GitHub Actions", graph_attr={"rank": "same"}):
            repo = Github("cowork-server")
            ci = GithubActions("cowork-prod-ci")
            cd = GithubActions("cowork-prod-cd")
            registry = Github("GHCR")
        bastion = InternetAlt1("ssh.gsmsv.site\nSSH 포트포워딩")
        operators = Users("운영자")

        nodes = platform(add_monitoring)
        network = nodes["network"].dot.name
        service_vms = nodes["service_vms"].dot.name
        o = observability

        repo >> edge(HTTP, "push main") >> ci >> edge(HTTP, "workflow_run") >> cd
        cd >> edge(HTTP, "build & push") >> registry
        cd >> edge(CONTROL, "OIDC 로그인", "dashed", constraint="false") >> nodes["vault"]
        cd >> edge(HTTP, "SSH") >> bastion
        bastion >> edge(HTTP, "Ansible / 슬롯 배포 (모든 VM)", lhead=network) >> nodes["gateway"]
        registry >> edge(DATA, "image pull", "dashed", lhead=network) >> nodes["gateway"]

        operators >> edge(HTTP, "cowork-monitoring.https.gsmsv.site") >> nodes["edge_proxy"]
        nodes["edge_proxy"] >> edge(HTTP, constraint="false") >> o["grafana"]
        o["grafana"] >> edge(DATA, style="dashed") >> o["prometheus"]
        o["grafana"] >> edge(DATA, style="dashed") >> o["loki"]
        nodes["config"] >> edge(OBS, "eureka_sd", "dashed", constraint="false") >> o["prometheus"]
        o["prometheus"] >> edge(OBS, "scrape\n/metrics, /actuator/prometheus", lhead=service_vms, constraint="false") >> nodes["apps"]["authorization"]
        o["prometheus"] >> edge(OBS) >> o["node_agent"]
        o["prometheus"] >> edge(OBS) >> o["db_exporter"]
        o["prometheus"] >> edge(OBS) >> o["exporters"]
        o["exporters"] >> edge(OBS, style="dotted", constraint="false") >> nodes["kafka"]
        o["exporters"] >> edge(OBS, style="dotted", constraint="false") >> nodes["redis"]
        o["log_agent"] >> edge(OBS, "push") >> o["loki"]
        o["prometheus"] >> edge(OBS) >> o["blackbox"] >> edge(OBS, "probe", "dashed", constraint="false") >> nodes["edge_proxy"]
        o["prometheus"] >> edge(OBS, "alerts") >> o["alertmanager"] >> edge(EXTERNAL, "장애 알림", constraint="false") >> nodes["discord"]


def delivery():
    with Diagram("cowork 배포 · 시크릿 흐름", filename=str(OUTPUT / "delivery"), show=False,
                 direction="LR", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
        with Cluster("GitHub"):
            repo = Github("cowork-server")
            ci = GithubActions("cowork-prod-ci")
            cd = GithubActions("cowork-prod-cd")
            registry = Github("GHCR")

        bastion = InternetAlt1("ssh.gsmsv.site\nSSH 포트포워딩")

        with Cluster("GSM SV 사설망"):
            vault = Vault("Vault")
            config = service("config", "Config + Eureka")
            with Cluster("각 VM"):
                infra = Docker("IaC 리소스\n방화벽 / DB / 스토리지")
                slot = service("{service}", "슬롯 컨테이너")

        repo >> edge(HTTP, "push main") >> ci >> edge(HTTP, "workflow_run") >> cd
        cd >> edge(HTTP, "build & push") >> registry
        cd >> edge(CONTROL, "OIDC 로그인", "dashed") >> vault
        cd >> edge(HTTP, "SSH") >> bastion
        bastion >> edge(HTTP, "Ansible") >> infra
        bastion >> edge(HTTP, "deploy-target") >> slot
        registry >> edge(DATA, "image pull", "dashed") >> slot
        vault >> edge(CONTROL, "slot-secrets") >> slot
        vault >> edge(CONTROL, "AppRole") >> config
        config >> edge(CONTROL, "설정 / 등록") >> slot


def monitoring():
    with Diagram("cowork 모니터링 구성", filename=str(OUTPUT / "monitoring"), show=False,
                 direction="TB", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
        operators = Users("운영자")

        with Cluster("Monitoring VM  10.0.0.137"):
            grafana = Grafana("Grafana")
            prometheus = Prometheus("Prometheus")
            loki = Loki("Loki")
            alertmanager = Prometheus("Alertmanager")
            blackbox = Prometheus("Blackbox\nexporter")
            exporters = Prometheus("Redis / Kafka\nexporter")

        eureka = service("config", "Eureka")

        with Cluster("각 VM"):
            app = service("{service}", "/metrics\n/actuator/prometheus")
            db_exporter = Prometheus("DB exporter")
            node_agent = Prometheus("node-exporter")
            log_agent = Docker("Grafana Alloy\nlog-agent")

        public = InternetAlt1("cowork.https.gsmsv.site")
        discord = Discord("Discord 알림")

        operators >> edge(HTTP, "cowork-monitoring.https.gsmsv.site") >> grafana
        grafana >> edge(DATA, style="dashed") >> prometheus
        grafana >> edge(DATA, style="dashed") >> loki
        eureka >> edge(OBS, "eureka_sd", "dashed") >> prometheus
        prometheus >> edge(OBS, "scrape") >> app
        prometheus >> edge(OBS) >> db_exporter
        prometheus >> edge(OBS) >> node_agent
        prometheus >> edge(OBS) >> exporters
        prometheus >> edge(OBS) >> blackbox >> edge(OBS, "probe", "dashed") >> public
        log_agent >> edge(OBS, "push") >> loki
        prometheus >> edge(OBS, "alerts") >> alertmanager >> edge(EXTERNAL) >> discord


if __name__ == "__main__":
    OUTPUT.mkdir(exist_ok=True)
    overview()
    delivery()
    monitoring()
    full()
