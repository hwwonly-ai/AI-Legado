# MVP 8 音色管理执行方案

## 1. 目标

把 MVP8 从“当前段轻量修正”调整为“音色管理”。基于现有书籍音色配置能力，提供一个类似 MultiTTS 的中文音色库界面：顶部选择阿里云或豆包 Provider，下面展示当前 Provider 的官方中文音色列表，每个音色以卡片呈现名称、已分配角色和声音属性，并提供明确的试听按钮和分配按钮，可把音色分配给书籍默认旁白/对白、角色或固定龙套。

MVP8 不做当前段修正，不改 AI Segment，不改剧本导入导出。

与 MVP8.1 的关系：

- MVP8 从音色列表出发，把音色分配给角色、书籍旁白/对白或龙套。
- MVP8.1 从角色编辑出发，为当前角色选择音色。
- MVP8 不改造角色编辑页入口；角色侧入口留到 MVP8.1。

## 2. 当前基础

已有能力：

- `BookVoiceConfigDialog`：当前书籍旁白/对白音色配置。
- `Book.setTtsVoices(ttsEngine, narratorVoice, dialogVoice)`：按 TTS 引擎保存书籍默认音色。
- `BookReadAloudRole.voices[engineKey]`：按 TTS 引擎保存角色音色。
- `BookRoleEditDialog`：角色编辑页已有跟随旁白、跟随对白、自定义音色选择。
- `HttpTTS`：HTTP_TTS Provider 配置来源。
- `HttpTTS.isSupportedRoleVoice()` / `voiceHint()`：判断是否支持分角色音色和显示 voice/speaker 提示。

## 3. 范围

### 包含

- 新增或扩展音色管理 Dialog。
- 顶部提供 TTS Provider 选择栏，只显示阿里云和豆包。
- 复用现有书籍音色表单的保存能力，但主界面改为“音色库列表”。
- 展示当前 Provider 的官方中文预置音色列表。
- 每个音色卡片提供“试听”按钮。
- 支持从右上角三点菜单打开试听文本编辑弹窗。
- 支持将音色分配给一个或多个角色。
- 支持把音色设为书籍默认旁白 / 默认对白。
- 支持清空某个角色音色，使其回退到默认规则。

### 不包含

- 不做当前段角色/音色/情绪修正。
- 不修改 AI 角色识别结果。
- 不管理阿里云和豆包之外的其他 TTS。
- 不导入导出音色库。
- 不做云端音色搜索。
- 不自动推荐音色。
- 不做试听语速、音量、语调调节。

## 4. 音色数据源

MVP8 采用内置预置音色列表，不在运行时抓取网页。

### 阿里云

来源：阿里云智能语音交互官方文档“音色列表”，只收录中文音色。

内置表字段固定为 4 列：

| 字段 | 含义 |
| --- | --- |
| `displayName` | UI 显示名称 |
| `requestValue` | 阿里云 `voice` 参数值 |
| `requestParam` | 固定为 `voice` |
| `voiceProperty` | 阿里云官方“类型” |

UI 只显示 `displayName`、`voiceProperty` 和已分配 `roleName`，不显示请求参数值。

### 豆包

来源：火山引擎官方文档“豆包语音合成模型2.0 音色列表”，只收录中文音色。

内置表字段固定为 4 列：

| 字段 | 含义 |
| --- | --- |
| `displayName` | UI 显示名称 |
| `requestValue` | 豆包 `voice_type` 参数值 |
| `requestParam` | 固定为 `voice_type` |
| `voiceProperty` | 豆包官方“场景” |

UI 只显示 `displayName`、`voiceProperty` 和已分配 `roleName`，不显示请求参数值。

### 维护方式

- 第一版可以手工整理为 Kotlin 常量或 JSON asset。
- 每个 Provider 单独一个中文音色列表，便于后续增量维护。
- 如果用户提供自定义音色，作为“自定义”分组追加在官方列表之后。

### 预置音色清单

下面表格即内置音色数据；UI 不显示 `requestValue`。

#### 阿里云中文音色

来源：阿里云智能语音交互官方“音色列表”。只收录支持中文、纯中文、中文及中英文混合、粤文或中文方言场景的音色，不收录纯英文、日语、印尼语、马来语等外语音色。

| displayName | requestValue | requestParam | voiceProperty |
| --- | --- | --- | --- |
| 阿斌 | abin | voice | 广东普通话 |
| 知小白 | zhixiaobai | voice | 普通话女声 |
| 知小夏 | zhixiaoxia | voice | 普通话女声 |
| 知小妹 | zhixiaomei | voice | 普通话女声 |
| 知柜 | zhigui | voice | 普通话女声 |
| 知硕 | zhishuo | voice | 普通话男声 |
| 艾夏 | aixia | voice | 普通话女声 |
| 知锋_多情感 | zhifeng_emo | voice | 多种情感男声 |
| 知冰_多情感 | zhibing_emo | voice | 多种情感男声 |
| 知妙_多情感 | zhimiao_emo | voice | 多种情感女声 |
| 知米_多情感 | zhimi_emo | voice | 多种情感女声 |
| 知燕_多情感 | zhiyan_emo | voice | 多种情感女声 |
| 知贝_多情感 | zhibei_emo | voice | 多种情感童声 |
| 知甜_多情感 | zhitian_emo | voice | 多种情感女声 |
| 小云 | xiaoyun | voice | 标准女声 |
| 小刚 | xiaogang | voice | 标准男声 |
| 若兮 | ruoxi | voice | 温柔女声 |
| 思琪 | siqi | voice | 温柔女声 |
| 思佳 | sijia | voice | 标准女声 |
| 思诚 | sicheng | voice | 标准男声 |
| 艾琪 | aiqi | voice | 温柔女声 |
| 艾佳 | aijia | voice | 标准女声 |
| 艾诚 | aicheng | voice | 标准男声 |
| 艾达 | aida | voice | 标准男声 |
| 宁儿 | ninger | voice | 标准女声 |
| 瑞琳 | ruilin | voice | 标准女声 |
| 思悦 | siyue | voice | 温柔女声 |
| 艾雅 | aiya | voice | 严厉女声 |
| 艾美 | aimei | voice | 甜美女声 |
| 艾雨 | aiyu | voice | 自然女声 |
| 艾悦 | aiyue | voice | 温柔女声 |
| 艾婧 | aijing | voice | 严厉女声 |
| 小美 | xiaomei | voice | 甜美女声 |
| 艾娜 | aina | voice | 浙普女声 |
| 伊娜 | yina | voice | 浙普女声 |
| 思婧 | sijing | voice | 严厉女声 |
| 思彤 | sitong | voice | 儿童音 |
| 小北 | xiaobei | voice | 萝莉女声 |
| 艾彤 | aitong | voice | 儿童音 |
| 艾薇 | aiwei | voice | 萝莉女声 |
| 艾宝 | aibao | voice | 萝莉女声 |
| 姗姗 | shanshan | voice | 粤语女声 |
| 艾媛 | aiyuan | voice | 知心姐姐 |
| 艾颖 | aiying | voice | 软萌童声 |
| 艾祥 | aixiang | voice | 磁性男声 |
| 艾墨 | aimo | voice | 情感男声 |
| 艾晔 | aiye | voice | 青年男声 |
| 艾婷 | aiting | voice | 电台女声 |
| 艾凡 | aifan | voice | 情感女声 |
| 小玥 | chuangirl | voice | 四川话女声 |
| 艾硕 | aishuo | voice | 自然男声 |
| 青青 | qingqing | voice | 中国台湾话女声 |
| 翠姐 | cuijie | voice | 东北话女声 |
| 小泽 | xiaoze | voice | 湖南重口音男声 |
| 艾楠 | ainan | voice | 广告男声 |
| 艾浩 | aihao | voice | 资讯男声 |
| 艾茗 | aiming | voice | 诙谐男声 |
| 艾笑 | aixiao | voice | 资讯女声 |
| 艾厨 | aichu | voice | 舌尖男声 |
| 艾倩 | aiqian | voice | 资讯女声 |
| 艾树 | aishu | voice | 资讯男声 |
| 艾茹 | airu | voice | 新闻女声 |
| 佳佳 | jiajia | voice | 粤语女声 |
| 桃子 | taozi | voice | 粤语女声 |
| 柜姐 | guijie | voice | 亲切女声 |
| Stella | stella | voice | 知性女声 |
| Stanley | stanley | voice | 沉稳男声 |
| Kenny | kenny | voice | 沉稳男声 |
| Rosa | rosa | voice | 自然女声 |
| 马树 | mashu | voice | 儿童剧男声 |
| 知琪 | zhiqi | voice | 温柔女声 |
| 知伦 | zhilun | voice | 悬疑解说 |
| 艾伦 | ailun | voice | 悬疑解说 |

#### 豆包语音合成模型2.0 中文音色

来源：用户提供的 `豆包音色 2.0.md`，对应火山引擎官方文档“豆包语音合成模型2.0 音色列表”。已按语种包含中文、且 `voice_type` 非英文音色筛选。

| displayName | requestValue | requestParam | voiceProperty |
| --- | --- | --- | --- |
| Vivi 2.0 | zh_female_vv_uranus_bigtts | voice_type | 通用场景 |
| 小何 2.0 | zh_female_xiaohe_uranus_bigtts | voice_type | 通用场景 |
| 云舟 2.0 | zh_male_m191_uranus_bigtts | voice_type | 通用场景 |
| 小天 2.0 | zh_male_taocheng_uranus_bigtts | voice_type | 通用场景 |
| 刘飞 2.0 | zh_male_liufei_uranus_bigtts | voice_type | 通用场景 |
| 魅力苏菲 2.0 | zh_female_sophie_uranus_bigtts | voice_type | 通用场景 |
| 清新女声 2.0 | zh_female_qingxinnvsheng_uranus_bigtts | voice_type | 通用场景 |
| 知性灿灿 2.0 | zh_female_cancan_uranus_bigtts | voice_type | 角色扮演 |
| 撒娇学妹 2.0 | zh_female_sajiaoxuemei_uranus_bigtts | voice_type | 角色扮演 |
| 甜美小源 2.0 | zh_female_tianmeixiaoyuan_uranus_bigtts | voice_type | 通用场景 |
| 甜美桃子 2.0 | zh_female_tianmeitaozi_uranus_bigtts | voice_type | 通用场景 |
| 爽快思思 2.0 | zh_female_shuangkuaisisi_uranus_bigtts | voice_type | 通用场景 |
| 佩奇猪 2.0 | zh_female_peiqi_uranus_bigtts | voice_type | 视频配音 |
| 邻家女孩 2.0 | zh_female_linjianvhai_uranus_bigtts | voice_type | 通用场景 |
| 少年梓辛/Brayan 2.0 | zh_male_shaonianzixin_uranus_bigtts | voice_type | 通用场景 |
| 猴哥 2.0 | zh_male_sunwukong_uranus_bigtts | voice_type | 视频配音 |
| Tina老师 2.0 | zh_female_yingyujiaoxue_uranus_bigtts | voice_type | 教育场景 |
| 暖阳女声 2.0 | zh_female_kefunvsheng_uranus_bigtts | voice_type | 客服场景 |
| 儿童绘本 2.0 | zh_female_xiaoxue_uranus_bigtts | voice_type | 有声阅读 |
| 大壹 2.0 | zh_male_dayi_uranus_bigtts | voice_type | 视频配音 |
| 黑猫侦探社咪仔 2.0 | zh_female_mizai_uranus_bigtts | voice_type | 视频配音 |
| 鸡汤女 2.0 | zh_female_jitangnv_uranus_bigtts | voice_type | 视频配音 |
| 魅力女友 2.0 | zh_female_meilinvyou_uranus_bigtts | voice_type | 通用场景 |
| 流畅女声 2.0 | zh_female_liuchangnv_uranus_bigtts | voice_type | 视频配音 |
| 儒雅逸辰 2.0 | zh_male_ruyayichen_uranus_bigtts | voice_type | 视频配音 |
| 温柔妈妈 2.0 | zh_female_wenroumama_uranus_bigtts | voice_type | 通用场景 |
| 解说小明 2.0 | zh_male_jieshuoxiaoming_uranus_bigtts | voice_type | 通用场景 |
| TVB女声 2.0 | zh_female_tvbnv_uranus_bigtts | voice_type | 通用场景 |
| 译制片男 2.0 | zh_male_yizhipiannan_uranus_bigtts | voice_type | 通用场景 |
| 俏皮女声 2.0 | zh_female_qiaopinv_uranus_bigtts | voice_type | 通用场景 |
| 直率英子 2.0 | zh_female_zhishuaiyingzi_uranus_bigtts | voice_type | 角色扮演 |
| 邻家男孩 2.0 | zh_male_linjiananhai_uranus_bigtts | voice_type | 通用场景 |
| 四郎 2.0 | zh_male_silang_uranus_bigtts | voice_type | 角色扮演 |
| 儒雅青年 2.0 | zh_male_ruyaqingnian_uranus_bigtts | voice_type | 通用场景 |
| 擎苍 2.0 | zh_male_qingcang_uranus_bigtts | voice_type | 角色扮演 |
| 熊二 2.0 | zh_male_xionger_uranus_bigtts | voice_type | 角色扮演 |
| 樱桃丸子 2.0 | zh_female_yingtaowanzi_uranus_bigtts | voice_type | 角色扮演 |
| 温暖阿虎/Alvin 2.0 | zh_male_wennuanahu_uranus_bigtts | voice_type | 通用场景 |
| 奶气萌娃 2.0 | zh_male_naiqimengwa_uranus_bigtts | voice_type | 通用场景 |
| 婆婆 2.0 | zh_female_popo_uranus_bigtts | voice_type | 通用场景 |
| 高冷御姐 2.0 | zh_female_gaolengyujie_uranus_bigtts | voice_type | 通用场景 |
| 傲娇霸总 2.0 | zh_male_aojiaobazong_uranus_bigtts | voice_type | 通用场景 |
| 懒音绵宝 2.0 | zh_male_lanyinmianbao_uranus_bigtts | voice_type | 角色扮演 |
| 反卷青年 2.0 | zh_male_fanjuanqingnian_uranus_bigtts | voice_type | 通用场景 |
| 温柔淑女 2.0 | zh_female_wenroushunv_uranus_bigtts | voice_type | 通用场景 |
| 古风少御 2.0 | zh_female_gufengshaoyu_uranus_bigtts | voice_type | 角色扮演 |
| 活力小哥 2.0 | zh_male_huolixiaoge_uranus_bigtts | voice_type | 通用场景 |
| 霸气青叔 2.0 | zh_male_baqiqingshu_uranus_bigtts | voice_type | 有声阅读 |
| 悬疑解说 2.0 | zh_male_xuanyijieshuo_uranus_bigtts | voice_type | 有声阅读 |
| 萌丫头/Cutey 2.0 | zh_female_mengyatou_uranus_bigtts | voice_type | 通用场景 |
| 贴心女声/Candy 2.0 | zh_female_tiexinnvsheng_uranus_bigtts | voice_type | 通用场景 |
| 鸡汤妹妹/Hope 2.0 | zh_female_jitangmei_uranus_bigtts | voice_type | 通用场景 |
| 磁性解说男声/Morgan 2.0 | zh_male_cixingjieshuonan_uranus_bigtts | voice_type | 通用场景 |
| 亮嗓萌仔 2.0 | zh_male_liangsangmengzai_uranus_bigtts | voice_type | 通用场景 |
| 开朗姐姐 2.0 | zh_female_kailangjiejie_uranus_bigtts | voice_type | 通用场景 |
| 高冷沉稳 2.0 | zh_male_gaolengchenwen_uranus_bigtts | voice_type | 通用场景 |
| 深夜播客 2.0 | zh_male_shenyeboke_uranus_bigtts | voice_type | 通用场景 |
| 鲁班七号 2.0 | zh_male_lubanqihao_uranus_bigtts | voice_type | 角色扮演 |
| 娇喘女声 2.0 | zh_female_jiaochuannv_uranus_bigtts | voice_type | 通用场景 |
| 林潇 2.0 | zh_female_linxiao_uranus_bigtts | voice_type | 角色扮演 |
| 玲玲姐姐 2.0 | zh_female_lingling_uranus_bigtts | voice_type | 角色扮演 |
| 春日部姐姐 2.0 | zh_female_chunribu_uranus_bigtts | voice_type | 角色扮演 |
| 唐僧 2.0 | zh_male_tangseng_uranus_bigtts | voice_type | 角色扮演 |
| 庄周 2.0 | zh_male_zhuangzhou_uranus_bigtts | voice_type | 角色扮演 |
| 开朗弟弟 2.0 | zh_male_kailangdidi_uranus_bigtts | voice_type | 通用场景 |
| 猪八戒 2.0 | zh_male_zhubajie_uranus_bigtts | voice_type | 角色扮演 |
| 感冒电音姐姐 2.0 | zh_female_ganmaodianyin_uranus_bigtts | voice_type | 角色扮演 |
| 谄媚女声 2.0 | zh_female_chanmeinv_uranus_bigtts | voice_type | 通用场景 |
| 女雷神 2.0 | zh_female_nvleishen_uranus_bigtts | voice_type | 角色扮演 |
| 亲切女声 2.0 | zh_female_qinqienv_uranus_bigtts | voice_type | 通用场景 |
| 快乐小东 2.0 | zh_male_kuailexiaodong_uranus_bigtts | voice_type | 通用场景 |
| 开朗学长 2.0 | zh_male_kailangxuezhang_uranus_bigtts | voice_type | 通用场景 |
| 悠悠君子 2.0 | zh_male_youyoujunzi_uranus_bigtts | voice_type | 通用场景 |
| 文静毛毛 2.0 | zh_female_wenjingmaomao_uranus_bigtts | voice_type | 通用场景 |
| 知性女声 2.0 | zh_female_zhixingnv_uranus_bigtts | voice_type | 通用场景 |
| 清爽男大 2.0 | zh_male_qingshuangnanda_uranus_bigtts | voice_type | 通用场景 |
| 渊博小叔 2.0 | zh_male_yuanboxiaoshu_uranus_bigtts | voice_type | 通用场景 |
| 阳光青年 2.0 | zh_male_yangguangqingnian_uranus_bigtts | voice_type | 通用场景 |
| 清澈梓梓 2.0 | zh_female_qingchezizi_uranus_bigtts | voice_type | 通用场景 |
| 甜美悦悦 2.0 | zh_female_tianmeiyueyue_uranus_bigtts | voice_type | 通用场景 |
| 心灵鸡汤 2.0 | zh_female_xinlingjitang_uranus_bigtts | voice_type | 通用场景 |
| 温柔小哥 2.0 | zh_male_wenrouxiaoge_uranus_bigtts | voice_type | 通用场景 |
| 柔美女友 2.0 | zh_female_roumeinvyou_uranus_bigtts | voice_type | 通用场景 |
| 东方浩然 2.0 | zh_male_dongfanghaoran_uranus_bigtts | voice_type | 通用场景 |
| 温柔小雅 2.0 | zh_female_wenrouxiaoya_uranus_bigtts | voice_type | 通用场景 |
| 天才童声 2.0 | zh_male_tiancaitongsheng_uranus_bigtts | voice_type | 通用场景 |
| 武则天 2.0 | zh_female_wuzetian_uranus_bigtts | voice_type | 角色扮演 |
| 顾姐 2.0 | zh_female_gujie_uranus_bigtts | voice_type | 角色扮演 |
| 广告解说 2.0 | zh_male_guanggaojieshuo_uranus_bigtts | voice_type | 通用场景 |
| 少儿故事 2.0 | zh_female_shaoergushi_uranus_bigtts | voice_type | 有声阅读 |
| 调皮公主 | saturn_zh_female_tiaopigongzhu_tob | voice_type | 角色扮演 |
| 爽朗少年 | saturn_zh_male_shuanglangshaonian_tob | voice_type | 角色扮演 |
| 天才同桌 | saturn_zh_male_tiancaitongzhuo_tob | voice_type | 角色扮演 |
| 知性灿灿 | saturn_zh_female_cancan_tob | voice_type | 角色扮演 |
| 傲娇女友 2.0 | saturn_zh_female_aojiaonvyou_tob | voice_type | 角色扮演 |
| 病娇姐姐 2.0 | saturn_zh_female_bingjiaojiejie_tob | voice_type | 角色扮演 |
| 成熟姐姐 2.0 | saturn_zh_female_chengshujiejie_tob | voice_type | 角色扮演 |
| 可爱女生 2.0 | saturn_zh_female_keainvsheng_tob | voice_type | 角色扮演 |
| 暖心学姐 2.0 | saturn_zh_female_nuanxinxuejie_tob | voice_type | 角色扮演 |
| 贴心女友 2.0 | saturn_zh_female_tiexinnvyou_tob | voice_type | 角色扮演 |
| 温柔文雅 2.0 | saturn_zh_female_wenrouwenya_tob | voice_type | 通用场景 |
| 妩媚御姐 2.0 | saturn_zh_female_wumeiyujie_tob | voice_type | 角色扮演 |
| 性感御姐 2.0 | saturn_zh_female_xingganyujie_tob | voice_type | 角色扮演 |
| 傲气凌人 2.0 | saturn_zh_male_aiqilingren_tob | voice_type | 角色扮演 |
| 傲娇公子 2.0 | saturn_zh_male_aojiaogongzi_tob | voice_type | 角色扮演 |
| 傲娇精英 2.0 | saturn_zh_male_aojiaojingying_tob | voice_type | 角色扮演 |
| 傲慢少爷 2.0 | saturn_zh_male_aomanshaoye_tob | voice_type | 角色扮演 |
| 霸道少爷 2.0 | saturn_zh_male_badaoshaoye_tob | voice_type | 角色扮演 |
| 病娇白莲 2.0 | saturn_zh_male_bingjiaobailian_tob | voice_type | 角色扮演 |
| 不羁青年 2.0 | saturn_zh_male_bujiqingnian_tob | voice_type | 角色扮演 |
| 成熟总裁 2.0 | saturn_zh_male_chengshuzongcai_tob | voice_type | 角色扮演 |
| 磁性男嗓 2.0 | saturn_zh_male_cixingnansang_tob | voice_type | 角色扮演 |
| 醋精男友 2.0 | saturn_zh_male_cujingnanyou_tob | voice_type | 角色扮演 |
| 风发少年 2.0 | saturn_zh_male_fengfashaonian_tob | voice_type | 角色扮演 |
| 腹黑公子 2.0 | saturn_zh_male_fuheigongzi_tob | voice_type | 角色扮演 |
| 轻盈朵朵 2.0 | saturn_zh_female_qingyingduoduo_cs_tob | voice_type | 客服场景 |
| 温婉珊珊 2.0 | saturn_zh_female_wenwanshanshan_cs_tob | voice_type | 客服场景 |
| 热情艾娜 2.0 | saturn_zh_female_reqingaina_cs_tob | voice_type | 客服场景 |
| 清新沐沐 2.0 | saturn_zh_male_qingxinmumu_cs_tob | voice_type | 客服场景 |

## 5. 数据策略

MVP8 不新增 Room 表。

书籍级隔离：

- 音色库是内置静态数据，可被所有书读取。
- 音色分配结果是书籍级配置，写入当前书的 `Book.ReadConfig` / `BookReadAloudRole.voices`。
- 不同书籍的旁白、默认对白、龙套和角色音色分配互不共享。
- 同一个 Provider 下，同一个角色只能在当前书内绑定一个音色；其他书的同名角色不受影响。

音色仍写入现有位置：

- 书籍默认旁白 / 对白：`Book.setTtsVoices(ttsEngine, narratorVoice, dialogVoice)`。
- 角色音色：`BookReadAloudRole.voices[ttsEngine] = requestValue`。
- 跟随同属性龙套：沿用 MVP7 约定的内部标记，例如 `@extra:middle_female`。
- 跟随书籍旁白 / 对白：`BookReadAloudRole.VOICE_BOOK_NARRATOR` / `BookReadAloudRole.VOICE_BOOK_DIALOG`。

官方音色条目是 UI 数据源，不是用户配置实体。只有当用户把某个音色分配给书籍或角色时，才把该音色的 `requestValue` 写入现有配置。

## 6. UI 设计

```text
┌────────────────────────────────────────────┐
│ 音色管理                                × │
├────────────────────────────────────────────┤
│ 音色管理        [豆包2.0 ▼]      🔍 ⋮     │
├────────────────────────────────────────────┤
│ 旁白音色                                │
│ 温柔淑女                                │
│ 场景：对话场景       已分配：旁白  试听 分配│
├────────────────────────────────────────────┤
│ 主角音色                                │
│ 灿灿                                    │
│ 场景：通用场景       已分配：张三  试听 分配│
├────────────────────────────────────────────┤
│ 未分配                                  │
│ 成熟大叔                                │
│ 场景：旁白场景       未分配        试听 分配│
└────────────────────────────────────────────┘
```

交互：

- 顶部 Provider 下拉只显示阿里云和豆包。
- Provider 切换后，列表刷新为该 Provider 的官方音色。
- 顶部搜索可按名称、已分配角色名、声音属性过滤。
- 试听文本入口放在右上角三点菜单中，不在主列表显示；点击“试听文本”菜单项后打开弹窗编辑试听文本。
- 已被分配给角色或书籍默认音色的音色显示在列表上方。
- 已分配音色按目标类型排序：旁白、默认对白、主角、龙套、主要配角、次要配角。
- 未分配音色显示在已分配音色之后。
- 点击卡片右侧“试听”按钮，使用试听文本和该音色试听。
- 点击“分配”打开角色选择器，可选择书籍旁白、书籍对白、8 个龙套或一个/多个角色。
- 一个角色在同一 Provider 下只能绑定一个音色；后一次分配会替换之前的分配。
- 卡片更多菜单提供“设为默认旁白”“设为默认对白”。
- 已被当前书籍或角色使用的音色，在卡片上显示“已分配：旁白 / 张三 / 龙套·中年女性”。
- 角色条目可在角色管理页清空音色，清空后按角色规则回退。

视觉规则：

- 采用纵向卡片列表，布局参考 MultiTTS，但不显示头像。
- 卡片主信息只显示 `displayName`、已分配角色名和声音属性，不显示请求参数值。
- 阿里云 `displayName` 显示官方“名称”，声音属性显示官方“类型”。
- 豆包 `displayName` 显示官方“音色名称”，声音属性显示官方“场景”。
- 卡片高度固定，长名称单行省略。
- 试听入口必须是明确按钮，可用“试听”文字或耳机图标加无障碍说明；分配使用文本按钮或更多菜单，避免主列表拥挤。
- 页面第一屏优先展示音色库，不把角色列表作为主内容。

## 7. 试听策略

试听文本：

- 默认：`你好，我是这个角色的试听文本。`
- 用户点击右上角三点菜单中的“试听文本”后，在弹窗中编辑文本，保存在本地偏好或当前 Dialog 状态。
- 主列表不直接展示试听文本正文，避免占用音色列表空间。
- 试听文本不进入角色档案、不进入 AI 缓存。

试听调用：

- 使用当前 Provider 的 HTTP_TTS 配置。
- 使用选中条目的 `requestParam` 和 `requestValue`。
- 使用短文本生成临时音频并播放。
- 试听音频放临时缓存，可复用普通音频播放能力，但不写章节音频缓存。

失败处理：

- Provider 不支持分角色音色：禁用列表编辑和试听，显示提示。
- `requestValue` 为空：使用该条目的回退音色试听；仍为空则禁用试听。
- 试听失败：显示错误，不影响保存。

## 8. 与角色系统的关系

- MVP7 的 8 个固定龙套可在 MVP8 里集中配置音色。
- MVP7.1 自动创建的次要配角默认跟随同属性龙套音色。
- 用户可在 MVP8 中给某个次要配角单独分配音色。
- 用户可把同一音色分配给多个角色。
- 每个角色在同一 Provider 下只能分配一个音色，重复分配时覆盖旧音色。
- 修改龙套音色后，跟随该龙套的次要配角自动使用新音色。

## 9. 缓存关系

MVP8 沿用 MVP7 测试期策略：

- 保存书籍默认音色、龙套音色或角色音色后，清理当前书当前章节音频缓存。
- 不清 AI Segment 缓存。
- 不清规则 Segment 缓存。
- 正式精确缓存失效留到 MVP10。

## 10. 验证计划

静态检查：

- `rg "BookVoiceConfigDialog|dialog_book_voice_config" app/src/main/java/io/legado/app app/src/main/res/layout`
- `rg "setTtsVoices|getNarratorVoice|getDialogVoice|getRoleVoice|setRoleVoice" app/src/main/java/io/legado/app`
- `rg "VOICE_BOOK_NARRATOR|VOICE_BOOK_DIALOG|@extra:" app/src/main/java/io/legado/app`
- `rg "voiceHint|isSupportedRoleVoice|providerName" app/src/main/java/io/legado/app/ui/book/read/config`
- 确认音色管理入口只展示阿里云和豆包。
- 确认音色卡片不显示请求参数值。
- 确认已分配音色排序优先于未分配音色。
- 确认已分配音色按旁白、默认对白、主角、龙套、主要配角、次要配角顺序显示。
- 确认 MVP8 不修改 AI 标注和 Segment 缓存 key。

Gradle 编译验证：

- `./gradlew :app:assembleDebug`

## 11. 验收用例

- 打开音色管理，默认选中当前 TTS Provider。
- 切换 Provider 后，音色库按 Provider 隔离展示。
- Provider 选择只包含阿里云和豆包。
- 阿里云 Provider 只展示阿里云官方中文音色列表。
- 豆包 Provider 只展示“豆包语音合成模型2.0”中文音色列表。
- 搜索音色名、已分配角色名或声音属性可以过滤列表。
- 点击右上角三点菜单中的“试听文本”后可在弹窗中修改试听文本。
- 主列表不展示试听文本正文。
- 主列表不展示请求参数值。
- 已分配音色显示在未分配音色上方。
- 已分配音色按旁白、默认对白、主角、龙套、主要配角、次要配角顺序显示。
- 点击任意音色卡片的“试听”按钮，使用该音色的 `requestParam` 和 `requestValue` 试听。
- 将某个 voice 设为书籍旁白后，旁白朗读使用该 voice。
- 将某个 voice 分配给角色“张三”，保存后张三朗读使用该 voice。
- 将同一个 voice 分配给“张三”和“李四”后，两个角色都使用该 voice。
- 再把另一个 voice 分配给“张三”后，新 voice 覆盖旧 voice。
- 清空角色音色后，角色回退到默认规则。
- 修改龙套音色后，跟随该龙套的次要配角使用新音色。
- 保存音色后，当前章节音频缓存被清理用于验证。

## 12. 完成定义

- MVP8 替代旧“当前段轻量修正”。
- 可按阿里云 / 豆包查看官方中文音色库。
- 阿里云和豆包2.0 中文音色列表可静态维护。
- 每个音色卡片提供明确的试听按钮。
- 试听文本可通过右上角三点菜单打开弹窗编辑。
- 不提供试听参数调节。
- 主列表不显示请求参数值。
- 已分配音色优先显示，并按旁白、默认对白、主角、龙套、主要配角、次要配角排序。
- 可把音色分配给不同角色。
- 每个角色在同一 Provider 下只有一个音色分配，重复分配会替换旧分配。
- 复用现有书籍音色和角色音色存储。
- 不新增数据库表、不升级数据库版本。
- 静态检查通过，`./gradlew :app:assembleDebug` 通过。
