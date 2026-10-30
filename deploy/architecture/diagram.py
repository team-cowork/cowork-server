from pathlib import Path

from diagrams import Cluster, Diagram, Edge, getdiagram
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

from overview_layout import OverviewDiagram

HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "out"
SEAWEEDFS_ICON = str(HERE / "icons" / "seaweedfs.png")
LIVEKIT_ICON = str(HERE / "icons" / "livekit.png")
SQLITE_ICON = str(HERE / "icons" / "sqlite.png")
FONT = "Apple SD Gothic Neo"
GRAPH = {
    "fontname": FONT, "fontsize": "24", "pad": "0.5",
    "splines": "ortho", "nodesep": "0.5", "ranksep": "0.9", "newrank": "true", "concentrate": "true",
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
    ("authorization", MySQL, "10.0.0.140"),
    ("user", MySQL, "10.0.0.185"),
    ("channel", MySQL, "10.0.0.74"),
    ("project", MySQL, "10.0.0.88"),
    ("team", MySQL, "10.0.0.97"),
    ("notification", SQLite, "10.0.0.131"),
    ("chat", MongoDB, "10.0.0.174"),
    ("roadmap", MySQL, "10.0.0.132"),
    ("voice", MongoDB, "10.0.0.90"),
    ("preference", PostgreSQL, "10.0.0.89"),
    ("github", None, "10.0.0.150"),
]
WITH_STORAGE = {"user", "team", "chat"}
REDIS_CLIENTS = {"authorization", "user", "chat", "preference", "github"}


def edge(color, label="", style="solid", **attrs):
    return Edge(color=color, style=style, xlabel=label, **attrs)


def service(name, detail=""):
    return Docker(f"cowork-{name}\n{detail}".strip())


def align(*nodes):
    with getdiagram().dot.subgraph() as row:
        row.attr(rank="same")
        for node in nodes:
            row.node(node.nodeid)


def platform(extend=None):
    nodes = {"clients": Users("Web / Mobile 클라이언트")}

    with Cluster("GSM SV Edge  (*.https.gsmsv.site)"):
        edge_proxy = ElbApplicationLoadBalancer("TLS 리버스 프록시")

    with Cluster("GSM SV 사설망  10.0.0.0/24") as network:
        with Cluster("10.0.0.127:8080\nS3 프록시 → user / team / chat"):
            gateway = service("gateway", "Nginx :8080\n:21000 / :21001 교대")

        with Cluster("Control Plane VMs"):
            management_label = ("10.0.0.111\nManagement VM / 배포 러너" if extend is None
                                else "10.0.0.111  Management VM / 배포 러너")
            with Cluster(management_label):
                consul = Docker("Consul\n서비스 메시 / ACL")
                nomad = Docker("Nomad\n단일 서버 / 스케줄러")
            with Cluster("10.0.0.91:8210  내부 TLS"):
                vault = Vault("Vault")

        apps = {}
        rows = []
        with Cluster("Service VMs  (서비스 + 전용 데이터)") as service_vms:
            for row in (SERVICES[:5], SERVICES[5:]):
                tops, bottoms = [], []
                for name, engine, address in row:
                    with Cluster(address):
                        app = service(name)
                        if engine is None:
                            apps[name] = app
                            tops.append(app)
                            continue
                        database = engine("notification.db\n영속 파일" if name == "notification" else f"{name}-db")
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
                kafka = Kafka("Kafka\nSASL_SSL / SCRAM")
            with Cluster("10.0.0.145  Redis VM"):
                redis = Redis("Redis")
            with Cluster("10.0.0.172:7880"):
                livekit = Custom("LiveKit SFU\nEdge WSS :7880", LIVEKIT_ICON)

        nodes.update(edge_proxy=edge_proxy, network=network, gateway=gateway,
                     consul=consul, nomad=nomad, vault=vault,
                     apps=apps, service_vms=service_vms, kafka=kafka, redis=redis, livekit=livekit)
        if extend:
            extend(nodes)

    with Cluster("External SaaS"):
        datagsm = InternetAlt1("DataGSM\nOAuth2 / Webhook")
        fcm = FCM("Firebase FCM")
        discord = Discord("Discord Webhook")
        github = Github("GitHub")
        oauth = InternetAlt1("계정 공유 OAuth (선택)\nGitHub / Google / Notion\nJira / Facebook")
    nodes.update(discord=discord, github=github)

    align(*rows[0][0], consul, nomad)
    align(*(app for app in rows[1][0] if app is not apps["github"]))

    nodes["clients"] >> edge(HTTP, "HTTPS / WSS / SSE") >> edge_proxy >> edge(HTTP) >> gateway
    nodes["clients"] >> edge(HTTP, "WebRTC TCP 38970 / UDP 35268\nVM TCP 7881 / UDP 7882", "dashed") >> livekit

    gateway >> edge(CONTROL, "Envoy / Consul 메시", "dashed") >> consul
    nomad >> edge(CONTROL, "Workload Identity", "dashed") >> vault
    gateway >> edge(DATA, "Rate limit / 배포 상태\nDataGSM Webhook 대기열", "dotted") >> redis

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

    apps["voice"] >> edge(HTTP, "Room API / Webhook\n(Envoy)", reverse=True) >> livekit
    apps["project"] >> edge(HTTP, "internal API (Envoy)") >> apps["github"]
    apps["github"] >> edge(EXTERNAL, "GitHub App API") >> github
    github >> edge(EXTERNAL, "GitHub 서명 Webhook", "dashed", constraint="false") >> edge_proxy
    apps["authorization"] >> edge(EXTERNAL, "OAuth2") >> datagsm
    datagsm >> edge(EXTERNAL, "DataGSM 서명 Webhook", "dashed", constraint="false") >> edge_proxy
    apps["channel"] >> edge(EXTERNAL, "계정 공유 OAuth") >> oauth
    apps["notification"] >> edge(EXTERNAL, "푸시") >> fcm
    apps["chat"] >> edge(EXTERNAL, "Chat 처리 오류 알림") >> discord
    return nodes


def overview():
    with OverviewDiagram("cowork 클라우드 아키텍처", filename=str(OUTPUT / "overview"), show=False, strict=True,
                 direction="TB", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
        platform()


def full():
    observability = {}

    def add_monitoring(nodes):
        with Cluster("10.0.0.137  Monitoring VM"):
            observability.update(
                grafana=Grafana("Grafana\nNginx :8080 → :3000"),
                prometheus=Prometheus("Prometheus"),
                loki=Loki("Loki\nmTLS :3100 → :3101"),
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
            cd = GithubActions("cowork-prod-cd\n매니페스트 커밋 → apply")
            registry = Github("GHCR")
        bastion = InternetAlt1("ssh.gsmsv.site\nSSH 포트포워딩")
        operators = Users("운영자")

        nodes = platform(add_monitoring)
        network = nodes["network"].dot.name
        service_vms = nodes["service_vms"].dot.name
        o = observability

        repo >> edge(HTTP, "main") >> ci >> edge(HTTP, "CI 성공") >> cd
        cd >> edge(HTTP, "push") >> registry
        cd >> edge(CONTROL, "관리 VM 러너 / OIDC", "dashed", constraint="false") >> nodes["vault"]
        cd >> edge(HTTP, "관리 VM 러너 / Ansible SSH") >> bastion
        bastion >> edge(HTTP, "Ansible 호스트 설정 (모든 VM)", lhead=network) >> nodes["gateway"]
        registry >> edge(DATA, "image pull", "dashed", lhead=network) >> nodes["gateway"]

        operators >> edge(HTTP, "cowork-monitoring.https.gsmsv.site") >> nodes["edge_proxy"]
        nodes["edge_proxy"] >> edge(HTTP, constraint="false") >> o["grafana"]
        o["grafana"] >> edge(DATA, style="dashed") >> o["prometheus"]
        o["grafana"] >> edge(DATA, style="dashed") >> o["loki"]
        nodes["consul"] << edge(OBS, "consul_sd\n로컬 에이전트 경유", "dashed", constraint="false") << o["prometheus"]
        o["prometheus"] >> edge(OBS, "scrape\n/metrics, /actuator/prometheus", lhead=service_vms, constraint="false") >> nodes["apps"]["authorization"]
        o["prometheus"] >> edge(OBS) >> o["node_agent"]
        o["prometheus"] >> edge(OBS) >> o["db_exporter"]
        o["prometheus"] >> edge(OBS) >> o["exporters"]
        o["exporters"] >> edge(OBS, style="dotted", constraint="false") >> nodes["kafka"]
        o["exporters"] >> edge(OBS, style="dotted", constraint="false") >> nodes["redis"]
        o["log_agent"] >> edge(OBS, "Nginx / mTLS push") >> o["loki"]
        o["prometheus"] >> edge(OBS) >> o["blackbox"] >> edge(OBS, "probe", "dashed", constraint="false") >> nodes["edge_proxy"]
        o["prometheus"] >> edge(OBS, "alerts") >> o["alertmanager"] >> edge(EXTERNAL, "장애 알림", constraint="false") >> nodes["discord"]

        align(repo, ci, cd, registry, operators, nodes["clients"], o["grafana"], o["log_agent"])


def delivery():
    with Diagram("cowork 배포 · 시크릿 흐름", filename=str(OUTPUT / "delivery"), show=False,
                 direction="LR", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
        with Cluster("GitHub"):
            repo = Github("cowork-server")
            ci = GithubActions("호스티드 Prod CI 검증\nProd CD 이미지 빌드")
            registry = Github("GHCR digest")
            manifest = Github("매니페스트 커밋\ndigest / 비밀 버전")
        with Cluster("관리 VM 10.0.0.111"):
            cd = GithubActions("sync-secrets\nmain 전용 배포 러너")
            apply = GithubActions("apply\n커밋된 상태 적용")
            nomad = Docker("Nomad 단일 서버\ndata → migration\napps → edge")
            consul = Docker("Consul 단일 서버")
        vault = Vault("Vault\n10.0.0.91:8210 / TLS")
        with Cluster("서비스 VM"):
            agent = Docker("Nomad / Consul 클라이언트")
            app = service("{service}", "할당 + Envoy")
            data = Docker("전용 DB / 스토리지")
        repo >> edge(HTTP, "main 릴리즈") >> ci >> edge(HTTP, "push") >> registry
        ci >> edge(CONTROL, "검증된 릴리즈") >> cd
        registry >> edge(DATA, "digest") >> manifest
        cd >> edge(CONTROL, "비밀 버전", constraint="false") >> manifest
        manifest >> edge(CONTROL, "커밋 후 적용") >> apply
        apply >> edge(CONTROL, "OpenTofu") >> nomad
        apply >> edge(CONTROL, "foundation / 접근 정책") >> consul
        apply >> edge(CONTROL, "Ansible 호스트 설정", constraint="false") >> agent
        apply >> edge(CONTROL, "OIDC / 배포 자격", constraint="false") >> vault
        cd >> edge(CONTROL, "OIDC / 비밀 버전") >> vault
        nomad >> edge(CONTROL, "잡 스케줄링") >> agent >> edge(HTTP) >> app
        registry >> edge(DATA, "image pull", "dashed") >> app
        vault >> edge(CONTROL, "Workload Identity\n고정 비밀 버전", "dashed") >> app
        consul >> edge(CONTROL, "mTLS / intentions", "dashed") >> app
        app >> edge(DATA, "Envoy") >> data


def monitoring():
    with Diagram("cowork 모니터링 구성", filename=str(OUTPUT / "monitoring"), show=False,
                 direction="TB", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
        operators = Users("운영자")

        with Cluster("Monitoring VM  10.0.0.137"):
            grafana = Grafana("Grafana\nNginx :8080 → :3000")
            prometheus = Prometheus("Prometheus")
            loki = Loki("Loki\nmTLS :3100 → :3101")
            alertmanager = Prometheus("Alertmanager")
            blackbox = Prometheus("Blackbox\nexporter")
            exporters = Prometheus("Redis / Kafka\nexporter")

        consul = Docker("Consul 로컬 에이전트\n10.0.0.137:8501")

        with Cluster("각 VM"):
            app = service("{service}", "/metrics\n/actuator/prometheus")
            db_exporter = Prometheus("DB exporter")
            node_agent = Prometheus("node-exporter")
            log_agent = Docker("Grafana Alloy\nlog-agent")
            platform_agent = Docker("Nomad / Consul\nVault VM 제외")

        public = InternetAlt1("PUBLIC_API_BASE_URL")
        vault = Vault("Vault\n10.0.0.91:8210")
        discord = Discord("Discord 알림")

        operators >> edge(HTTP, "GSM SV TLS 프록시\ncowork-monitoring.https.gsmsv.site") >> grafana
        grafana >> edge(DATA, style="dashed") >> prometheus
        grafana >> edge(DATA, style="dashed") >> loki
        grafana >> edge(DATA, style="dashed", constraint="false") >> alertmanager
        consul << edge(OBS, "consul_sd / TLS", "dashed") << prometheus
        prometheus >> edge(OBS, "scrape") >> app
        prometheus >> edge(OBS) >> db_exporter
        prometheus >> edge(OBS) >> node_agent
        prometheus >> edge(OBS) >> exporters
        prometheus >> edge(OBS, "HTTPS metrics") >> platform_agent
        prometheus >> edge(OBS, "metrics / probe", constraint="false") >> log_agent
        log_agent >> edge(OBS, "로컬 readiness", constraint="false") >> app
        prometheus >> edge(OBS) >> blackbox >> edge(OBS, "probe", "dashed") >> public
        blackbox >> edge(OBS, "TLS / health", "dashed") >> vault
        log_agent >> edge(OBS, "Nginx / mTLS push") >> loki
        loki >> edge(OBS, "로그 / Vault audit 알림", constraint="false") >> alertmanager
        prometheus >> edge(OBS, "alerts") >> alertmanager >> edge(EXTERNAL) >> discord

        align(grafana, consul, log_agent, platform_agent)
        align(prometheus, loki)
        align(alertmanager, blackbox, exporters, app, db_exporter, node_agent)


if __name__ == "__main__":
    OUTPUT.mkdir(exist_ok=True)
    overview()
    delivery()
    monitoring()
    full()
