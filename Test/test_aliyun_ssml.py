#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import hmac
import hashlib
import base64
import json
import urllib.parse
import uuid
import sys
import os
import subprocess
import platform
import re
from datetime import datetime, timezone
import requests

# 你的凭证
ACCESS_KEY_ID = "LTAI5t6c76sJ1APVcbgTBFrP"
ACCESS_KEY_SECRET = "HGsNUjSWnzlRm89GTOP9rIgWVVYROZ"
APP_KEY = "pE3O7UT1W1mHCqHq"
VOICE = "zhifeng_emo"

def percent_encode(value):
    """按 Aliyun 规范进行百分比编码"""
    encoded = urllib.parse.quote(value, safe='')
    encoded = encoded.replace('+', '%20')
    encoded = encoded.replace('*', '%2A')
    encoded = encoded.replace('%7E', '~')
    return encoded

def sign(string_to_sign, access_key_secret):
    """生成签名"""
    key = (access_key_secret + '&').encode('utf-8')
    message = string_to_sign.encode('utf-8')
    signature = base64.b64encode(
        hmac.new(key, message, hashlib.sha1).digest()
    ).decode('utf-8')
    return signature

def play_audio(file_path):
    """播放音频文件"""
    if not os.path.exists(file_path):
        print(f"[!] 文件不存在: {file_path}")
        return False
    
    system = platform.system()
    try:
        if system == "Darwin":  # macOS
            subprocess.run(["afplay", file_path], check=True, timeout=60)
        elif system == "Linux":
            # 尝试 paplay（PulseAudio）
            if os.path.exists("/usr/bin/paplay"):
                subprocess.run(["paplay", file_path], check=True, timeout=60)
            # 尝试 aplay（ALSA）
            elif os.path.exists("/usr/bin/aplay"):
                subprocess.run(["aplay", file_path], check=True, timeout=60)
            else:
                print(f"[!] Linux 没有找到音频播放器（需要 paplay 或 aplay）")
                return False
        elif system == "Windows":
            os.startfile(file_path)
        else:
            print(f"[!] 不支持的操作系统: {system}")
            return False
        
        print(f"[+] 播放完成")
        return True
    except subprocess.TimeoutExpired:
        print(f"[!] 播放超时")
        return False
    except Exception as e:
        print(f"[!] 播放异常: {e}")
        return False

def get_token():
    """获取 Aliyun 语音 Token"""
    # 生成时间戳（UTC）
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    
    # 生成查询参数
    query_params = {
        'AccessKeyId': ACCESS_KEY_ID,
        'Action': 'CreateToken',
        'Format': 'JSON',
        'RegionId': 'cn-shanghai',
        'SignatureMethod': 'HMAC-SHA1',
        'SignatureNonce': str(uuid.uuid4()),
        'SignatureVersion': '1.0',
        'Timestamp': timestamp,
        'Version': '2019-02-28'
    }
    
    # 排序构造查询字符串
    sorted_params = sorted(query_params.items())
    query_string = '&'.join(f'{k}={percent_encode(v)}' for k, v in sorted_params)
    
    # 构造待签名字符串
    string_to_sign = f"GET&{percent_encode('/')}&{percent_encode(query_string)}"
    
    # 生成签名
    signature = sign(string_to_sign, ACCESS_KEY_SECRET)
    
    # 构造完整 URL
    url = f"http://nls-meta.cn-shanghai.aliyuncs.com/?{query_string}&Signature={percent_encode(signature)}"
    
    print(f"[*] 请求 Token URL: {url}")
    print()
    
    # 请求 token
    response = requests.get(url, timeout=10)
    print(f"[*] Token 响应状态码: {response.status_code}")
    
    result = response.json()
    if result.get('Message'):
        print(f"[!] 错误: {result['Message']}")
        return None
    
    token = result.get('Token', {}).get('Id')
    print(f"[+] Token 获取成功: {token[:20]}...")
    print()
    return token

def synthesize_audio(token, text, output_file="/tmp/output.mp3", play=True):
    """直接合成文本为音频"""
    
    # 检查是否包含 SSML 标签
    is_ssml = '<speak>' in text or '<emotion>' in text
    
    # 构造请求体
    payload = {
        "appkey": APP_KEY,
        "text": text,
        "format": "mp3",
        "volume": 100,
        "speech_rate": 0,
        "voice_name": VOICE,
        "ssml": is_ssml
    }
    
    headers = {
        "X-NLS-Token": token,
        "Content-Type": "application/json"
    }
    
    url = "https://nls-gateway.cn-shanghai.aliyuncs.com/stream/v1/tts"
    
    print(f"[*] 文本长度: {len(text)} 字符")
    print(f"[*] 请求 URL: {url}")
    print()
    
    try:
        # 使用 requests 的 json 参数自动处理序列化
        response = requests.post(url, json=payload, headers=headers, timeout=30)
        print(f"[*] 响应状态码: {response.status_code}")
        print(f"[*] Content-Type: {response.headers.get('Content-Type')}")
        print()
        
        if response.status_code == 200:
            content_type = response.headers.get('Content-Type', '')
            if 'audio' in content_type:
                print(f"[+] ✓ 成功! 返回音频数据，大小: {len(response.content)} 字节")
                # 保存到文件
                with open(output_file, 'wb') as f:
                    f.write(response.content)
                print(f"[+] 音频已保存到: {output_file}")
                
                # 播放音频
                if play:
                    print(f"[*] 开始播放...")
                    play_audio(output_file)
                
                return True
            else:
                # 错误响应
                error_msg = response.text
                print(f"[!] 服务器返回错误: {error_msg}")
                try:
                    error_json = response.json()
                    print(f"[!] 错误详情: {error_json}")
                except:
                    pass
                return False
        else:
            print(f"[!] HTTP 错误 {response.status_code}")
            print(f"[!] 响应: {response.text[:500]}")
            return False
    
    except Exception as e:
        print(f"[!] 请求异常: {e}")
        return False

def read_text_file(file_path):
    """读取文本文件"""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read().strip()
        if not content:
            print(f"[!] 文件为空: {file_path}")
            return None
        print(f"[+] 已读取文件: {file_path}")
        print(f"[+] 文本内容 (含 SSML): {content[:150]}")
        print()
        return content
    except FileNotFoundError:
        print(f"[!] 文件不存在: {file_path}")
        return None
    except Exception as e:
        print(f"[!] 读取文件异常: {e}")
        return None

def parse_ssml_segments(content):
    """解析文本中的多个 <speak> 段"""
    segments = []
    start = 0
    while True:
        speak_start = content.find('<speak', start)
        if speak_start == -1:
            break
        speak_end = content.find('</speak>', speak_start)
        if speak_end == -1:
            break
        segment = content[speak_start:speak_end+8].strip()
        segments.append(segment)
        start = speak_end + 8
    return segments

def main():
    print("=" * 70)
    print("Aliyun TTS SSML 文本转语音")
    print("=" * 70)
    print()
    
    # 检查命令行参数
    ssml_segments = ['<speak voice="zhifeng_emo"><emotion category="neutral" intensity="1.0">你好世界</emotion></speak>']
    base_name = "output"
    
    if len(sys.argv) > 1:
        file_path = sys.argv[1]
        content = read_text_file(file_path)
        if content:
            # 解析多个 SSML 段
            ssml_segments = parse_ssml_segments(content)
            if not ssml_segments:
                ssml_segments = [content]
            base_name = os.path.splitext(os.path.basename(file_path))[0]
            print(f"[+] 解析到 {len(ssml_segments)} 个 SSML 段落")
            print()
    else:
        print(f"[*] 使用默认 SSML")
        print()
    
    # 第一步：获取 Token
    print("[步骤 1] 获取 Aliyun Token")
    print("-" * 70)
    token = get_token()
    
    if not token:
        print("[!] 获取 Token 失败，停止测试")
        return
    
    # 第二步：逐个合成音频
    print("[步骤 2] 合成音频")
    print("-" * 70)
    
    results = []
    
    for i, ssml_text in enumerate(ssml_segments, 1):
        print()
        print(f"{'='*70}")
        print(f"[{i}/{len(ssml_segments)}] 合成段落")
        print(f"{'='*70}")
        print(f"[*] SSML: {ssml_text[:80]}...")
        
        output_file = f'/tmp/{base_name}_{i}.mp3'
        success = synthesize_audio(token, ssml_text, output_file=output_file, play=True)
        results.append((i, success))
        print()
    
    # 总结
    print("=" * 70)
    print("合成结果")
    print("=" * 70)
    for i, success in results:
        print(f"{'✓' if success else '✗'} 段落 {i}")
    print(f"总计: {len(results)} 个段落，成功 {sum(1 for _, s in results if s)} 个")
    print()

if __name__ == "__main__":
    main()