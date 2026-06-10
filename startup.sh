
#!/bin/bash

# 定义颜色输出，增强可读性
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 1. init: 下载需要的软件
do_init() {
    log_info "开始初始化环境..."
    log_info "检查并下载依赖软件..."
    sudo apt install openjdk-21-jdk lsof
    npm install pnpm
    log_info "依赖软件安装完成。"
}

# 2. build: 编译前后端
do_build() {
    log_info "开始编译项目..."
	export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    log_info "编译后端代码..."
    mvn clean install -DskipTests
    log_info "编译前端代码..."
    pushd mateclaw-ui
    pnpm install
    popd
    log_info "项目编译完成。"
}

# 3. 启动后端
start_backend() {
    log_info "正在启动后端服务..."
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    pushd mateclaw-server
    mvn spring-boot:run  &
    popd
    log_info "后端服务已启动 (PID: simulated_pid_backend)。"
}

# 4. 启动前端
start_frontend() {
    log_info "正在启动前端服务..."
    # 实际场景中可能是: npm start 或 nginx 重启
    pushd mateclaw-ui
    pnpm dev &
    popd 
    log_info "前端服务已启动 (PID: simulated_pid_frontend)。"
}

# 5. 终止后端
stop_backend() {
    log_info "正在停止后端服务..."
    # 实际场景中可能是: kill -9 $(cat backend.pid)
    # 方法1：通过端口号查找并终止（MateClaw 默认后端端口 18088）
	PORT=18088
	PID=$(lsof -ti :$PORT)
	if [ -n "$PID" ]; then
		log_info "正在终止端口 $PORT 上的进程 (PID: $PID)..."
		kill $PID
		log_info "后端服务已停止。"
	else
		echo "未找到监听端口 $PORT 的进程"
	fi

}

# 6. 终止前端
stop_frontend() {
    log_info "正在停止前端服务..."
    # 实际场景中可能是: kill frontend process
	PORT=5173
	PID=$(lsof -ti :$PORT)
	if [ -n "$PID" ]; then
		echo "正在终止端口 $PORT 上的进程 (PID: $PID)..."
		kill $PID
		log_info "前端服务已停止。"
	else
		log_info "未找到监听端口 $PORT 的进程"
	fi
}

# 7. 重启前后端
restart_all() {
    log_warn "正在重启前后端服务..."
    stop_backend
    stop_frontend
    sleep 1
    start_backend
    start_frontend
    log_info "前后端服务重启完成。"
}

# 8. 重启后端
restart_backend() {
    log_warn "正在重启后端服务..."
    stop_backend
    sleep 1
    start_backend
    log_info "后端服务重启完成。"
}

# 显示帮助信息
show_help() {
    echo "用法: $0 <command>"
    echo ""
    echo "可用命令:"
    echo "  init            初始化环境，下载依赖软件"
    echo "  build           编译前后端代码"
    echo "  start           启动后端和前端服务"
    echo "  stop            停止后端和前端服务"
    echo "  restart         重启后端和前端服务"
    echo "  restart-backend 仅重启后端服务"
    echo "  help            显示此帮助信息"
}

# 主逻辑：根据参数执行不同流程
case "$1" in
    init)
        do_init
        ;;
    build)
        do_build
        ;;
    start)
        start_backend
        start_frontend
        ;;
    stop)
        stop_backend
        stop_frontend
        ;;
    restart)
        restart_all
        ;;
    restart-backend)
        restart_backend
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        log_error "未知命令: $1"
        show_help
        exit 1
        ;;
esac

