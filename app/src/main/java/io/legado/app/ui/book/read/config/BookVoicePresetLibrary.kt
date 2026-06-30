package io.legado.app.ui.book.read.config

enum class BookVoiceProvider {
    ALIYUN,
    DOUBAO
}

data class BookVoicePreset(
    val displayName: String,
    val requestValue: String,
    val requestParam: String,
    val voiceProperty: String,
    val provider: BookVoiceProvider
)

sealed class BookVoiceAssignmentTarget {
    object Narrator : BookVoiceAssignmentTarget()
    object Dialog : BookVoiceAssignmentTarget()
    data class Role(val roleId: String) : BookVoiceAssignmentTarget()
}

object BookVoicePresetLibrary {

    fun presets(provider: BookVoiceProvider): List<BookVoicePreset> {
        return when (provider) {
            BookVoiceProvider.ALIYUN -> aliyun
            BookVoiceProvider.DOUBAO -> doubao
        }
    }

    private fun parse(provider: BookVoiceProvider, raw: String): List<BookVoicePreset> {
        return raw.trim().lineSequence()
            .map { it.split('\t') }
            .filter { it.size == 4 }
            .map {
                BookVoicePreset(
                    displayName = it[0].trim(),
                    requestValue = it[1].trim(),
                    requestParam = it[2].trim(),
                    voiceProperty = it[3].trim(),
                    provider = provider
                )
            }
            .toList()
    }

    private val aliyun by lazy {
        parse(
            BookVoiceProvider.ALIYUN,
            """
阿斌	abin	voice	广东普通话
知小白	zhixiaobai	voice	普通话女声
知小夏	zhixiaoxia	voice	普通话女声
知小妹	zhixiaomei	voice	普通话女声
知柜	zhigui	voice	普通话女声
知硕	zhishuo	voice	普通话男声
艾夏	aixia	voice	普通话女声
知锋_多情感	zhifeng_emo	voice	多种情感男声
知冰_多情感	zhibing_emo	voice	多种情感男声
知妙_多情感	zhimiao_emo	voice	多种情感女声
知米_多情感	zhimi_emo	voice	多种情感女声
知燕_多情感	zhiyan_emo	voice	多种情感女声
知贝_多情感	zhibei_emo	voice	多种情感童声
知甜_多情感	zhitian_emo	voice	多种情感女声
小云	xiaoyun	voice	标准女声
小刚	xiaogang	voice	标准男声
若兮	ruoxi	voice	温柔女声
思琪	siqi	voice	温柔女声
思佳	sijia	voice	标准女声
思诚	sicheng	voice	标准男声
艾琪	aiqi	voice	温柔女声
艾佳	aijia	voice	标准女声
艾诚	aicheng	voice	标准男声
艾达	aida	voice	标准男声
宁儿	ninger	voice	标准女声
瑞琳	ruilin	voice	标准女声
思悦	siyue	voice	温柔女声
艾雅	aiya	voice	严厉女声
艾美	aimei	voice	甜美女声
艾雨	aiyu	voice	自然女声
艾悦	aiyue	voice	温柔女声
艾婧	aijing	voice	严厉女声
小美	xiaomei	voice	甜美女声
艾娜	aina	voice	浙普女声
伊娜	yina	voice	浙普女声
思婧	sijing	voice	严厉女声
思彤	sitong	voice	儿童音
小北	xiaobei	voice	萝莉女声
艾彤	aitong	voice	儿童音
艾薇	aiwei	voice	萝莉女声
艾宝	aibao	voice	萝莉女声
姗姗	shanshan	voice	粤语女声
艾媛	aiyuan	voice	知心姐姐
艾颖	aiying	voice	软萌童声
艾祥	aixiang	voice	磁性男声
艾墨	aimo	voice	情感男声
艾晔	aiye	voice	青年男声
艾婷	aiting	voice	电台女声
艾凡	aifan	voice	情感女声
小玥	chuangirl	voice	四川话女声
艾硕	aishuo	voice	自然男声
青青	qingqing	voice	中国台湾话女声
翠姐	cuijie	voice	东北话女声
小泽	xiaoze	voice	湖南重口音男声
艾楠	ainan	voice	广告男声
艾浩	aihao	voice	资讯男声
艾茗	aiming	voice	诙谐男声
艾笑	aixiao	voice	资讯女声
艾厨	aichu	voice	舌尖男声
艾倩	aiqian	voice	资讯女声
艾树	aishu	voice	资讯男声
艾茹	airu	voice	新闻女声
佳佳	jiajia	voice	粤语女声
桃子	taozi	voice	粤语女声
柜姐	guijie	voice	亲切女声
Stella	stella	voice	知性女声
Stanley	stanley	voice	沉稳男声
Kenny	kenny	voice	沉稳男声
Rosa	rosa	voice	自然女声
马树	mashu	voice	儿童剧男声
知琪	zhiqi	voice	温柔女声
知伦	zhilun	voice	悬疑解说
艾伦	ailun	voice	悬疑解说
            """
        )
    }

    private val doubao by lazy {
        parse(
            BookVoiceProvider.DOUBAO,
            """
Vivi 2.0	zh_female_vv_uranus_bigtts	voice_type	通用场景
小何 2.0	zh_female_xiaohe_uranus_bigtts	voice_type	通用场景
云舟 2.0	zh_male_m191_uranus_bigtts	voice_type	通用场景
小天 2.0	zh_male_taocheng_uranus_bigtts	voice_type	通用场景
刘飞 2.0	zh_male_liufei_uranus_bigtts	voice_type	通用场景
魅力苏菲 2.0	zh_female_sophie_uranus_bigtts	voice_type	通用场景
清新女声 2.0	zh_female_qingxinnvsheng_uranus_bigtts	voice_type	通用场景
知性灿灿 2.0	zh_female_cancan_uranus_bigtts	voice_type	角色扮演
撒娇学妹 2.0	zh_female_sajiaoxuemei_uranus_bigtts	voice_type	角色扮演
甜美小源 2.0	zh_female_tianmeixiaoyuan_uranus_bigtts	voice_type	通用场景
甜美桃子 2.0	zh_female_tianmeitaozi_uranus_bigtts	voice_type	通用场景
爽快思思 2.0	zh_female_shuangkuaisisi_uranus_bigtts	voice_type	通用场景
佩奇猪 2.0	zh_female_peiqi_uranus_bigtts	voice_type	视频配音
邻家女孩 2.0	zh_female_linjianvhai_uranus_bigtts	voice_type	通用场景
少年梓辛/Brayan 2.0	zh_male_shaonianzixin_uranus_bigtts	voice_type	通用场景
猴哥 2.0	zh_male_sunwukong_uranus_bigtts	voice_type	视频配音
Tina老师 2.0	zh_female_yingyujiaoxue_uranus_bigtts	voice_type	教育场景
暖阳女声 2.0	zh_female_kefunvsheng_uranus_bigtts	voice_type	客服场景
儿童绘本 2.0	zh_female_xiaoxue_uranus_bigtts	voice_type	有声阅读
大壹 2.0	zh_male_dayi_uranus_bigtts	voice_type	视频配音
黑猫侦探社咪仔 2.0	zh_female_mizai_uranus_bigtts	voice_type	视频配音
鸡汤女 2.0	zh_female_jitangnv_uranus_bigtts	voice_type	视频配音
魅力女友 2.0	zh_female_meilinvyou_uranus_bigtts	voice_type	通用场景
流畅女声 2.0	zh_female_liuchangnv_uranus_bigtts	voice_type	视频配音
儒雅逸辰 2.0	zh_male_ruyayichen_uranus_bigtts	voice_type	视频配音
温柔妈妈 2.0	zh_female_wenroumama_uranus_bigtts	voice_type	通用场景
解说小明 2.0	zh_male_jieshuoxiaoming_uranus_bigtts	voice_type	通用场景
TVB女声 2.0	zh_female_tvbnv_uranus_bigtts	voice_type	通用场景
译制片男 2.0	zh_male_yizhipiannan_uranus_bigtts	voice_type	通用场景
俏皮女声 2.0	zh_female_qiaopinv_uranus_bigtts	voice_type	通用场景
直率英子 2.0	zh_female_zhishuaiyingzi_uranus_bigtts	voice_type	角色扮演
邻家男孩 2.0	zh_male_linjiananhai_uranus_bigtts	voice_type	通用场景
四郎 2.0	zh_male_silang_uranus_bigtts	voice_type	角色扮演
儒雅青年 2.0	zh_male_ruyaqingnian_uranus_bigtts	voice_type	通用场景
擎苍 2.0	zh_male_qingcang_uranus_bigtts	voice_type	角色扮演
熊二 2.0	zh_male_xionger_uranus_bigtts	voice_type	角色扮演
樱桃丸子 2.0	zh_female_yingtaowanzi_uranus_bigtts	voice_type	角色扮演
温暖阿虎/Alvin 2.0	zh_male_wennuanahu_uranus_bigtts	voice_type	通用场景
奶气萌娃 2.0	zh_male_naiqimengwa_uranus_bigtts	voice_type	通用场景
婆婆 2.0	zh_female_popo_uranus_bigtts	voice_type	通用场景
高冷御姐 2.0	zh_female_gaolengyujie_uranus_bigtts	voice_type	通用场景
傲娇霸总 2.0	zh_male_aojiaobazong_uranus_bigtts	voice_type	通用场景
懒音绵宝 2.0	zh_male_lanyinmianbao_uranus_bigtts	voice_type	角色扮演
反卷青年 2.0	zh_male_fanjuanqingnian_uranus_bigtts	voice_type	通用场景
温柔淑女 2.0	zh_female_wenroushunv_uranus_bigtts	voice_type	通用场景
古风少御 2.0	zh_female_gufengshaoyu_uranus_bigtts	voice_type	角色扮演
活力小哥 2.0	zh_male_huolixiaoge_uranus_bigtts	voice_type	通用场景
霸气青叔 2.0	zh_male_baqiqingshu_uranus_bigtts	voice_type	有声阅读
悬疑解说 2.0	zh_male_xuanyijieshuo_uranus_bigtts	voice_type	有声阅读
萌丫头/Cutey 2.0	zh_female_mengyatou_uranus_bigtts	voice_type	通用场景
贴心女声/Candy 2.0	zh_female_tiexinnvsheng_uranus_bigtts	voice_type	通用场景
鸡汤妹妹/Hope 2.0	zh_female_jitangmei_uranus_bigtts	voice_type	通用场景
磁性解说男声/Morgan 2.0	zh_male_cixingjieshuonan_uranus_bigtts	voice_type	通用场景
亮嗓萌仔 2.0	zh_male_liangsangmengzai_uranus_bigtts	voice_type	通用场景
开朗姐姐 2.0	zh_female_kailangjiejie_uranus_bigtts	voice_type	通用场景
高冷沉稳 2.0	zh_male_gaolengchenwen_uranus_bigtts	voice_type	通用场景
深夜播客 2.0	zh_male_shenyeboke_uranus_bigtts	voice_type	通用场景
鲁班七号 2.0	zh_male_lubanqihao_uranus_bigtts	voice_type	角色扮演
娇喘女声 2.0	zh_female_jiaochuannv_uranus_bigtts	voice_type	通用场景
林潇 2.0	zh_female_linxiao_uranus_bigtts	voice_type	角色扮演
玲玲姐姐 2.0	zh_female_lingling_uranus_bigtts	voice_type	角色扮演
春日部姐姐 2.0	zh_female_chunribu_uranus_bigtts	voice_type	角色扮演
唐僧 2.0	zh_male_tangseng_uranus_bigtts	voice_type	角色扮演
庄周 2.0	zh_male_zhuangzhou_uranus_bigtts	voice_type	角色扮演
开朗弟弟 2.0	zh_male_kailangdidi_uranus_bigtts	voice_type	通用场景
猪八戒 2.0	zh_male_zhubajie_uranus_bigtts	voice_type	角色扮演
感冒电音姐姐 2.0	zh_female_ganmaodianyin_uranus_bigtts	voice_type	角色扮演
谄媚女声 2.0	zh_female_chanmeinv_uranus_bigtts	voice_type	通用场景
女雷神 2.0	zh_female_nvleishen_uranus_bigtts	voice_type	角色扮演
亲切女声 2.0	zh_female_qinqienv_uranus_bigtts	voice_type	通用场景
快乐小东 2.0	zh_male_kuailexiaodong_uranus_bigtts	voice_type	通用场景
开朗学长 2.0	zh_male_kailangxuezhang_uranus_bigtts	voice_type	通用场景
悠悠君子 2.0	zh_male_youyoujunzi_uranus_bigtts	voice_type	通用场景
文静毛毛 2.0	zh_female_wenjingmaomao_uranus_bigtts	voice_type	通用场景
知性女声 2.0	zh_female_zhixingnv_uranus_bigtts	voice_type	通用场景
清爽男大 2.0	zh_male_qingshuangnanda_uranus_bigtts	voice_type	通用场景
渊博小叔 2.0	zh_male_yuanboxiaoshu_uranus_bigtts	voice_type	通用场景
阳光青年 2.0	zh_male_yangguangqingnian_uranus_bigtts	voice_type	通用场景
清澈梓梓 2.0	zh_female_qingchezizi_uranus_bigtts	voice_type	通用场景
甜美悦悦 2.0	zh_female_tianmeiyueyue_uranus_bigtts	voice_type	通用场景
心灵鸡汤 2.0	zh_female_xinlingjitang_uranus_bigtts	voice_type	通用场景
温柔小哥 2.0	zh_male_wenrouxiaoge_uranus_bigtts	voice_type	通用场景
柔美女友 2.0	zh_female_roumeinvyou_uranus_bigtts	voice_type	通用场景
东方浩然 2.0	zh_male_dongfanghaoran_uranus_bigtts	voice_type	通用场景
温柔小雅 2.0	zh_female_wenrouxiaoya_uranus_bigtts	voice_type	通用场景
天才童声 2.0	zh_male_tiancaitongsheng_uranus_bigtts	voice_type	通用场景
武则天 2.0	zh_female_wuzetian_uranus_bigtts	voice_type	角色扮演
顾姐 2.0	zh_female_gujie_uranus_bigtts	voice_type	角色扮演
广告解说 2.0	zh_male_guanggaojieshuo_uranus_bigtts	voice_type	通用场景
少儿故事 2.0	zh_female_shaoergushi_uranus_bigtts	voice_type	有声阅读
调皮公主	saturn_zh_female_tiaopigongzhu_tob	voice_type	角色扮演
爽朗少年	saturn_zh_male_shuanglangshaonian_tob	voice_type	角色扮演
天才同桌	saturn_zh_male_tiancaitongzhuo_tob	voice_type	角色扮演
知性灿灿	saturn_zh_female_cancan_tob	voice_type	角色扮演
傲娇女友 2.0	saturn_zh_female_aojiaonvyou_tob	voice_type	角色扮演
病娇姐姐 2.0	saturn_zh_female_bingjiaojiejie_tob	voice_type	角色扮演
成熟姐姐 2.0	saturn_zh_female_chengshujiejie_tob	voice_type	角色扮演
可爱女生 2.0	saturn_zh_female_keainvsheng_tob	voice_type	角色扮演
暖心学姐 2.0	saturn_zh_female_nuanxinxuejie_tob	voice_type	角色扮演
贴心女友 2.0	saturn_zh_female_tiexinnvyou_tob	voice_type	角色扮演
温柔文雅 2.0	saturn_zh_female_wenrouwenya_tob	voice_type	通用场景
妩媚御姐 2.0	saturn_zh_female_wumeiyujie_tob	voice_type	角色扮演
性感御姐 2.0	saturn_zh_female_xingganyujie_tob	voice_type	角色扮演
傲气凌人 2.0	saturn_zh_male_aiqilingren_tob	voice_type	角色扮演
傲娇公子 2.0	saturn_zh_male_aojiaogongzi_tob	voice_type	角色扮演
傲娇精英 2.0	saturn_zh_male_aojiaojingying_tob	voice_type	角色扮演
傲慢少爷 2.0	saturn_zh_male_aomanshaoye_tob	voice_type	角色扮演
霸道少爷 2.0	saturn_zh_male_badaoshaoye_tob	voice_type	角色扮演
病娇白莲 2.0	saturn_zh_male_bingjiaobailian_tob	voice_type	角色扮演
不羁青年 2.0	saturn_zh_male_bujiqingnian_tob	voice_type	角色扮演
成熟总裁 2.0	saturn_zh_male_chengshuzongcai_tob	voice_type	角色扮演
磁性男嗓 2.0	saturn_zh_male_cixingnansang_tob	voice_type	角色扮演
醋精男友 2.0	saturn_zh_male_cujingnanyou_tob	voice_type	角色扮演
风发少年 2.0	saturn_zh_male_fengfashaonian_tob	voice_type	角色扮演
腹黑公子 2.0	saturn_zh_male_fuheigongzi_tob	voice_type	角色扮演
轻盈朵朵 2.0	saturn_zh_female_qingyingduoduo_cs_tob	voice_type	客服场景
温婉珊珊 2.0	saturn_zh_female_wenwanshanshan_cs_tob	voice_type	客服场景
热情艾娜 2.0	saturn_zh_female_reqingaina_cs_tob	voice_type	客服场景
清新沐沐 2.0	saturn_zh_male_qingxinmumu_cs_tob	voice_type	客服场景
            """
        )
    }
}
