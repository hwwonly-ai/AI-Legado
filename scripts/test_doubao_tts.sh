#!/bin/bash
# 豆包 TTS V3 接口测试脚本
# 用法: ./scripts/test_doubao_tts.sh [app_id] [access_token] [speaker]

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 显示信息
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# 配置
APP_ID="${1:-}"
ACCESS_TOKEN="${2:-}"
SPEAKER="${3:-zh_female_vv_uranus_bigtts}"
RESOURCE_ID="seed-tts-2.0"
ENDPOINT="https://openspeech.bytedance.com/api/v3/tts/unidirectional"

# 显示使用说明
show_usage() {
    echo "豆包 TTS V3 接口测试脚本"
    echo ""
    echo "用法: $0 [app_id] [access_token] [speaker]"
    echo ""
    echo "参数:"
    echo "  app_id        豆包 App ID（必填）"
    echo "  access_token  豆包 Access Token（必填）"
    echo "  speaker       说话人 ID（可选，默认: zh_female_vv_uranus_bigtts）"
    echo ""
    echo "环境变量:"
    echo "  DOUBAO_TTS_APP_ID        设置 App ID"
    echo "  DOUBAO_TTS_ACCESS_TOKEN  设置 Access Token"
    echo "  DOUBAO_TTS_SPEAKER       设置 Speaker ID"
    echo ""
    echo "示例:"
    echo "  # 使用参数"
    echo "  $0 3375438334 PFx-n_ffssXh0bE- zh_female_vv_uranus_bigtts"
    echo ""
    echo "  # 使用环境变量"
    echo "  export DOUBAO_TTS_APP_ID=3375438334"
    echo "  export DOUBAO_TTS_ACCESS_TOKEN=PFx-n_ffssXh0bE-"
    echo "  $0"
}

# 检查依赖
check_dependencies() {
    info "检查依赖..."

    if ! command -v curl &> /dev/null; then
        error "curl 未安装，请先安装 curl"
        exit 1
    fi

    if ! command -v jq &> /dev/null; then
        warning "jq 未安装，将无法格式化输出"
    fi

    success "依赖检查完成"
}

# 读取凭证
read_credentials() {
    info "读取凭证..."

    # 优先级：参数 > 环境变量 > 提示输入
    if [ -z "$APP_ID" ]; then
        APP_ID="${DOUBAO_TTS_APP_ID:-}"
    fi

    if [ -z "$ACCESS_TOKEN" ]; then
        ACCESS_TOKEN="${DOUBAO_TTS_ACCESS_TOKEN:-}"
    fi

    if [ -z "$SPEAKER" ] || [ "$SPEAKER" == "zh_female_vv_uranus_bigtts" ]; then
        SPEAKER="${DOUBAO_TTS_SPEAKER:-zh_female_vv_uranus_bigtts}"
    fi

    # 如果还是为空，提示用户
    if [ -z "$APP_ID" ]; then
        read -p "请输入 App ID: " APP_ID
    fi

    if [ -z "$ACCESS_TOKEN" ]; then
        read -sp "请输入 Access Token: " ACCESS_TOKEN
        echo ""
    fi

    # 验证凭证
    if [ -z "$APP_ID" ] || [ -z "$ACCESS_TOKEN" ]; then
        error "缺少凭证信息"
        show_usage
        exit 1
    fi

    success "凭证已读取"
}

# 生成请求体
generate_request_body() {
    local text="$1"
    local uid="legado_doubao_tts"

    cat <<EOF
{
    "user": {
        "uid": "$uid"
    },
    "req_params": {
        "text": "$text",
        "speaker": "$SPEAKER",
        "audio_params": {
            "format": "mp3",
            "sample_rate": 24000,
            "speech_rate": 0,
            "enable_timestamp": false
        },
        "additions": {
            "explicit_language": "zh",
            "disable_markdown_filter": true,
            "disable_emoji_filter": true
        }
    }
}
EOF
}

# 发送 API 请求
send_request() {
    local text="$1"
    local request_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
    local request_body=$(generate_request_body "$text")

    info "发送请求..."
    echo "  文本: $text"
    echo "  Speaker: $SPEAKER"
    echo "  Resource ID: $RESOURCE_ID"
    echo "  Request ID: $request_id"

    # 发送请求
    local response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -H "X-Api-App-Key: $APP_ID" \
        -H "X-Api-Access-Key: $ACCESS_TOKEN" \
        -H "X-Api-Resource-Id: $RESOURCE_ID" \
        -H "X-Api-Request-Id: $request_id" \
        -d "$request_body" \
        "$ENDPOINT")

    echo ""
    echo "响应:"

    # 尝试格式化输出（如果有 jq）
    if command -v jq &> /dev/null; then
        echo "$response" | jq '.' || echo "$response"
    else
        echo "$response"
    fi

    # 解析响应
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)

    if [ "$code" == "0" ]; then
        success "请求成功！"

        # 提取音频数据（如果有）
        local data=$(echo "$response" | grep -o '"data":"[^"]*"' | cut -d'"' -f4)
        if [ -n "$data" ]; then
            local audio_file="test_audio_$(date +%s).mp3"
            echo "$data" | base64 -d > "$audio_file" 2>/dev/null && \
                success "音频已保存到: $audio_file" || \
                warning "音频保存失败"
        fi
    else
        local message=$(echo "$response" | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
        error "请求失败！错误代码: $code，错误信息: $message"
        return 1
    fi
}

# 运行单元测试
run_unit_tests() {
    info "运行单元测试..."

    if [ -f "gradlew" ]; then
        ./gradlew :app:testAppDebugUnitTest || {
            error "单元测试失败"
            return 1
        }
        success "单元测试通过"
    else
        error "gradlew 文件未找到，请在项目根目录运行此脚本"
        return 1
    fi
}

# 运行集成测试
run_integration_tests() {
    info "运行集成测试..."

    if [ -f "gradlew" ]; then
        DOUBAO_TTS_APP_ID="$APP_ID" \
        DOUBAO_TTS_ACCESS_TOKEN="$ACCESS_TOKEN" \
        DOUBAO_TTS_SPEAKER="$SPEAKER" \
        ./gradlew :app:testAppDebugUnitTest || {
            error "集成测试失败"
            return 1
        }
        success "集成测试通过"
    else
        error "gradlew 文件未找到，请在项目根目录运行此脚本"
        return 1
    fi
}

# 主函数
main() {
    echo "=========================================="
    echo "豆包 TTS V3 接口测试脚本"
    echo "=========================================="
    echo ""

    # 检查是否请求帮助
    if [ "$1" == "-h" ] || [ "$1" == "--help" ]; then
        show_usage
        exit 0
    fi

    check_dependencies
    read_credentials

    echo ""
    echo "=========================================="
    echo "测试选项"
    echo "=========================================="
    echo "1. 快速 API 测试（简单请求）"
    echo "2. 运行单元测试"
    echo "3. 运行集成测试（需要凭证）"
    echo "4. 全部运行"
    echo "0. 退出"
    echo ""
    read -p "请选择 (0-4): " choice

    case $choice in
        1)
            send_request "你好，这是豆包语音合成 V3 测试。"
            ;;
        2)
            run_unit_tests
            ;;
        3)
            run_integration_tests
            ;;
        4)
            run_unit_tests && echo "" && send_request "你好，这是豆包语音合成 V3 测试。" && echo "" && run_integration_tests
            ;;
        0)
            info "退出"
            exit 0
            ;;
        *)
            error "无效选择"
            exit 1
            ;;
    esac
}

# 运行主函数
main "$@"
