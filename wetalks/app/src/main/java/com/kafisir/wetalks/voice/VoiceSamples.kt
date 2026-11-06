package com.kafisir.wetalks.voice

/** The sentence each language is tried with on the Natural voices screen: about WeTalks itself (private, end-to-end encrypted), not generic filler. */
object VoiceSamples {
    class Sample(val code: String, val label: String, val text: String)

    /** Languages the voice can speak, in the order they are listed under "Show more". "hinglish" is Hindi written in English letters. */
    val all: List<Sample> = listOf(
        Sample("en", "English", "Welcome to WeTalks, your private messenger. Every message and call is end-to-end encrypted, so only you and your friends can read them."),
        Sample("hi", "हिन्दी", "वीटॉक्स में आपका स्वागत है। यहाँ आपके मैसेज और कॉल पूरी तरह एन्क्रिप्टेड रहते हैं, इन्हें सिर्फ़ आप और आपके दोस्त ही देख सकते हैं।"),
        Sample("hinglish", "Hinglish", "WeTalks mein aapka swagat hai yaar. Yahan aapke messages aur calls end-to-end encrypted hain, to sirf aap aur aapke dost hi unhe padh sakte hain."),
        Sample("ar", "العربية", "مرحباً بك في ويتوكس، تطبيق المراسلة الخاص بك. رسائلك مشفّرة من طرف إلى طرف، ولا يستطيع قراءتها سواك أنت وأصدقاؤك."),
        Sample("bg", "Български", "Добре дошли в WeTalks, вашия личен месинджър. Съобщенията ви са криптирани от край до край и само вие и приятелите ви можете да ги четете."),
        Sample("cs", "Čeština", "Vítejte ve WeTalks, vaší soukromé komunikační aplikaci. Vaše zprávy jsou šifrované od začátku do konce a přečtete je jen vy a vaši přátelé."),
        Sample("da", "Dansk", "Velkommen til WeTalks, din private messenger. Dine beskeder er krypteret fra ende til anden, så kun du og dine venner kan læse dem."),
        Sample("de", "Deutsch", "Willkommen bei WeTalks, deinem privaten Messenger. Deine Nachrichten sind Ende-zu-Ende-verschlüsselt, sodass nur du und deine Freunde sie lesen können."),
        Sample("el", "Ελληνικά", "Καλώς ήρθατε στο WeTalks, τον ιδιωτικό σας messenger. Τα μηνύματά σας είναι κρυπτογραφημένα από άκρο σε άκρο και μόνο εσείς και οι φίλοι σας μπορείτε να τα διαβάσετε."),
        Sample("es", "Español", "Te damos la bienvenida a WeTalks, tu mensajero privado. Tus mensajes están cifrados de extremo a extremo, así que solo tú y tus amigos pueden leerlos."),
        Sample("et", "Eesti", "Tere tulemast WeTalksi, teie privaatsesse sõnumirakendusse. Teie sõnumid on otspunktkrüpteeritud, nii et neid saate lugeda ainult teie ja teie sõbrad."),
        Sample("fi", "Suomi", "Tervetuloa WeTalksiin, yksityiseen viestisovellukseesi. Viestisi on salattu päästä päähän, joten vain sinä ja ystäväsi voitte lukea ne."),
        Sample("fr", "Français", "Bienvenue sur WeTalks, votre messagerie privée. Vos messages sont chiffrés de bout en bout : seuls vous et vos amis pouvez les lire."),
        Sample("hr", "Hrvatski", "Dobrodošli u WeTalks, vaš privatni messenger. Vaše su poruke šifrirane od kraja do kraja pa ih možete čitati samo vi i vaši prijatelji."),
        Sample("hu", "Magyar", "Üdvözöljük a WeTalksben, a privát üzenetküldőjében. Az üzenetei végponttól végpontig titkosítottak, így csak Ön és a barátai olvashatják őket."),
        Sample("id", "Indonesia", "Selamat datang di WeTalks, aplikasi pesan pribadi Anda. Pesan Anda dienkripsi dari ujung ke ujung, jadi hanya Anda dan teman Anda yang bisa membacanya."),
        Sample("it", "Italiano", "Benvenuto su WeTalks, il tuo messenger privato. I tuoi messaggi sono cifrati end-to-end, quindi solo tu e i tuoi amici potete leggerli."),
        Sample("ja", "日本語", "プライベートメッセンジャー、WeTalksへようこそ。メッセージはエンドツーエンドで暗号化されているので、あなたと友達だけが読めます。"),
        Sample("ko", "한국어", "프라이빗 메신저 WeTalks에 오신 것을 환영합니다. 메시지는 종단 간 암호화되어 있어 나와 친구만 읽을 수 있습니다."),
        Sample("lt", "Lietuvių", "Sveiki atvykę į WeTalks, jūsų privačią žinučių programą. Jūsų žinutės užšifruotos nuo galo iki galo, todėl jas gali perskaityti tik jūs ir jūsų draugai."),
        Sample("lv", "Latviešu", "Laipni lūdzam WeTalks, jūsu privātajā ziņapmaiņas lietotnē. Jūsu ziņojumi ir šifrēti no gala līdz galam, tāpēc tos varat izlasīt tikai jūs un jūsu draugi."),
        Sample("nl", "Nederlands", "Welkom bij WeTalks, je privé-messenger. Je berichten zijn end-to-end versleuteld, zodat alleen jij en je vrienden ze kunnen lezen."),
        Sample("pl", "Polski", "Witamy w WeTalks, Twoim prywatnym komunikatorze. Twoje wiadomości są szyfrowane end-to-end, więc możesz je czytać tylko ty i twoi znajomi."),
        Sample("pt", "Português", "Bem-vindo ao WeTalks, o seu mensageiro privado. As suas mensagens têm criptografia de ponta a ponta, por isso só você e os seus amigos podem lê-las."),
        Sample("ro", "Română", "Bine ați venit la WeTalks, aplicația dvs. privată de mesagerie. Mesajele dvs. sunt criptate end-to-end, așa că doar dvs. și prietenii dvs. le puteți citi."),
        Sample("ru", "Русский", "Добро пожаловать в WeTalks, ваш личный мессенджер. Ваши сообщения защищены сквозным шифрованием, поэтому прочитать их можете только вы и ваши друзья."),
        Sample("sk", "Slovenčina", "Vitajte vo WeTalks, vašej súkromnej komunikačnej aplikácii. Vaše správy sú šifrované od začiatku do konca, takže ich môžete čítať len vy a vaši priatelia."),
        Sample("sl", "Slovenščina", "Dobrodošli v WeTalks, vašem zasebnem sporočilniku. Vaša sporočila so šifrirana od konca do konca, zato jih lahko berete samo vi in vaši prijatelji."),
        Sample("sv", "Svenska", "Välkommen till WeTalks, din privata meddelandeapp. Dina meddelanden är krypterade från ände till ände, så bara du och dina vänner kan läsa dem."),
        Sample("tr", "Türkçe", "Özel mesajlaşma uygulamanız WeTalks'e hoş geldiniz. Mesajlarınız uçtan uca şifrelidir, bu yüzden yalnızca siz ve arkadaşlarınız okuyabilir."),
        Sample("uk", "Українська", "Ласкаво просимо до WeTalks, вашого приватного месенджера. Ваші повідомлення захищені наскрізним шифруванням, тож прочитати їх можете лише ви та ваші друзі."),
        Sample("vi", "Tiếng Việt", "Chào mừng bạn đến với WeTalks, ứng dụng nhắn tin riêng tư của bạn. Tin nhắn được mã hóa đầu cuối nên chỉ bạn và bạn bè mới đọc được."),
    )

    fun byCode(code: String) = all.firstOrNull { it.code == code }

    /** The sample for the app's own language: Hinglish stays Hinglish; a language the voice cannot speak falls back to English. */
    fun forAppLanguage(appLang: String): Sample = byCode(if (appLang == "hi-Latn") "hinglish" else appLang) ?: all[0]
}
