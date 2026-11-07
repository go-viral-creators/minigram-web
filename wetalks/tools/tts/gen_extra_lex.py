#!/usr/bin/env python3
"""Builds app/src/main/assets/hinglish/hi_lex_extra.tsv: the verb forms and common words the base list (hi_lex.tsv) does not have yet.
Only keys that are missing from the base list are written, so the base list always wins. Re-run after editing the tables below."""
import os, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
base_p = os.path.join(root, 'app/src/main/assets/hinglish/hi_lex.tsv')
out_p = os.path.join(root, 'app/src/main/assets/hinglish/hi_lex_extra.tsv')
base = {l.split('\t')[0] for l in open(base_p, encoding='utf8') if '\t' in l}
out = {}
# real English words (and names) that a verb paradigm happens to produce: never turn them into Hindi
EXCLUDE = set('ani ate dare doge gate hansen jake jane jodi jodie karen kari lake lane late mare marie marta pane pita sochi sunni todo waite pair sir'.split())
def put(k, v):
    if k not in base and k not in out and k not in EXCLUDE: out[k] = v

# stems that end in a consonant: dekh-na, dekh-ta, dekh-o ...
C_STEMS = [('kar','कर'),('dekh','देख'),('bol','बोल'),('chal','चल'),('sun','सुन'),('samajh','समझ'),('bhej','भेज'),('mil','मिल'),('likh','लिख'),('padh','पढ़'),
 ('pahunch','पहुँच'),('soch','सोच'),('rakh','रख'),('reh','रह'),('rah','रह'),('ruk','रुक'),('uth','उठ'),('baith','बैठ'),('pooch','पूछ'),('puch','पूछ'),('lag','लग'),('maan','मान'),('jaan','जान'),
 ('pehchan','पहचान'),('chhod','छोड़'),('khol','खोल'),('pakad','पकड़'),('sikh','सीख'),('jeet','जीत'),('haar','हार'),('bhool','भूल'),('khel','खेल'),('daud','दौड़'),('dhoond','ढूँढ'),
 ('bech','बेच'),('kharid','ख़रीद'),('bhar','भर'),('nikal','निकल'),('ghum','घूम'),('nach','नाच'),('hans','हँस'),('maang','माँग'),('rok','रोक'),('tod','तोड़'),('jod','जोड़'),
 ('bhag','भाग'),('bhaag','भाग'),('pad','पड़'),('badal','बदल'),('dar','डर'),('mar','मर'),('maar','मार'),('chaah','चाह'),('keh','कह'),('kah','कह'),('dikh','दिख'),
 ('hil','हिल'),('kaat','काट'),('chhup','छुप'),('soch','सोच'),('bhoolna'[:-3],'भूल'),('sambhal','सँभल'),('khatam kar'[:0] or 'khatam','ख़तम'),('shuru','शुरू'),('band','बंद'),('yaad','याद'),('intezaar','इंतज़ार'),('wait','वेट')]
C_END = [('na','ना'),('ne','ने'),('ni','नी'),('ta','ता'),('ti','ती'),('te','ते'),('a','ा'),('i','ी'),('e','े'),('unga','ूँगा'),('ungi','ूँगी'),('enge','ेंगे'),('oge','ोगे'),('ogi','ोगी'),
 ('ega','ेगा'),('egi','ेगी'),('o','ो'),('iye','िए'),('ie','िए'),('ijiye','िजिए'),('ein','ें'),('en','ें'),('un','ूँ'),('kar','कर'),('ke','के')]
# stems that end in the long vowel aa: bata-na, bata-ya, bata-o ...
A_STEMS = [('bata','बता'),('dikha','दिखा'),('sikha','सिखा'),('laga','लगा'),('bana','बना'),('utha','उठा'),('hata','हटा'),('suna','सुना'),('jaga','जगा'),('bhula','भुला'),('chala','चला'),
 ('samjha','समझा'),('kha','खा'),('pa','पा'),('la','ला'),('ga','गा'),('ja','जा'),('a','आ'),('naha','नहा'),('gira','गिरा'),('uda','उड़ा'),('bhaga','भगा'),('rula','रुला'),('hansa','हँसा'),
 ('mila','मिला'),('mita','मिटा'),('pahuncha','पहुँचा'),('sula','सुला'),('jala','जला'),('bula','बुला'),('daba','दबा'),('bacha','बचा'),('lauta','लौटा'),('badla','बदला')]
A_END = [('na','ना'),('ne','ने'),('ni','नी'),('ta','ता'),('ti','ती'),('te','ते'),('ya','या'),('yi','ई'),('ye','ए'),('o','ओ'),('iye','इए'),('unga','ऊँगा'),('ungi','ऊँगी'),('yega','एगा'),
 ('yegi','एगी'),('yenge','एँगे'),('oge','ओगे'),('ogi','ओगी'),('yein','एँ'),('yen','एँ'),('un','ऊँ'),('kar','कर'),('ke','के')]
for r, d in C_STEMS:
    for s, ds in C_END: put(r + s, d + ds)
for r, d in A_STEMS:
    for rv in ([r, r + 'a'] if r.endswith('a') else [r]):   # khana and khaana
        for s, ds in A_END:
            if r == 'a' and s in ('a',): continue
            put(rv + s, d + ds)
    if r == 'a':
        for s, ds in A_END: put('aa' + s, d + ds)
# irregular verbs
IRR = '''kiya किया|kiye किए|ki की|kijiye कीजिए|karoge करोगे|karogi करोगी|karun करूँ|karoon करूँ|karke करके
diya दिया|diye दिए|di दी|do दो|dijiye दीजिए|dunga दूँगा|dungi दूँगी|dega देगा|degi देगी|denge देंगे|doge दोगे|dogi दोगी|dein दें|dekar देकर|dena देना|deta देता|deti देती|dete देते
liya लिया|liye लिए|li ली|lo लो|lijiye लीजिए|lunga लूँगा|lungi लूँगी|lega लेगा|legi लेगी|lenge लेंगे|loge लोगे|logi लोगी|lein लें|lekar लेकर|lena लेना|leta लेता|leti लेती|lete लेते
pina पीना|peena पीना|pita पीता|peeta पीता|piti पीती|peeti पीती|piya पिया|piyo पियो|piyega पिएगा|piyenge पिएँगे|piunga पिऊँगा|pikar पीकर
hona होना|hota होता|hoti होती|hote होते|hua हुआ|hui हुई|hue हुए|hoga होगा|hogi होगी|honge होंगे|hokar होकर|hoon हूँ|honge होंगे
sona सोना|sota सोता|soti सोती|sote सोते|soya सोया|soyi सोई|soye सोए|sounga सोऊँगा|soyega सोएगा|sokar सोकर
rona रोना|rota रोता|roti रोती|rote रोते|roya रोया|royi रोई|roye रोए|rounga रोऊँगा|royega रोएगा|rokar रोकर
gaya गया|gayi गई|gaye गए|jaunga जाऊँगा|jaungi जाऊँगी|jayega जाएगा|jayegi जाएगी|jayenge जाएँगे|jaoge जाओगे|jao जाओ|jaiye जाइए|jakar जाकर|jana जाना|jaana जाना
aaya आया|aayi आई|aaye आए|aaunga आऊँगा|aaungi आऊँगी|aayega आएगा|aayegi आएगी|aayenge आएँगे|aaoge आओगे|aao आओ|aaiye आइए|aakar आकर|aana आना|aata आता|aati आती|aate आते
raha रहा|rahi रही|rahe रहे|rahega रहेगा|rahegi रहेगी|rahenge रहेंगे|raho रहो|rehna रहना|rehta रहता|rehti रहती|rehte रहते
chahiye चाहिए|chahta चाहता|chahti चाहती|chahte चाहते|chahunga चाहूँगा
vishwas विश्वास|shaayad शायद|shayad शायद|jhooth झूठ|jhoot झूठ|skool स्कूल|bacha बच्चा|bachche बच्चे|bacche बच्चे|dukhi दुखी|sukhi सुखी|dimaag दिमाग़|dimag दिमाग़|aankh आँख|aankhen आँखें
haath हाथ|pair पैर|sir सर|hafta हफ़्ता|hafte हफ़्ते|mahina महीना|mahine महीने|dada दादा|dadi दादी|nana नाना|nani नानी|chacha चाचा|chachi चाची|mama मामा|mami मामी|bhabhi भाभी|didi दीदी|bhaiya भैया
pati पति|patni पत्नी|dosti दोस्ती|dushman दुश्मन|ishq इश्क़|duniya दुनिया|gaon गाँव|shehar शहर|desh देश|sadak सड़क|gaadi गाड़ी|paani पानी|doodh दूध|chawal चावल|sabzi सब्ज़ी|mithai मिठाई
rupaye रुपये|mehnat मेहनत|naukri नौकरी|aasan आसान|dheere धीरे|zor ज़ोर|zyada ज़्यादा|kaafi काफ़ी|sirf सिर्फ़|pakka पक्का|sacchi सच्ची|sachchi सच्ची|purana पुराना|purani पुरानी|lamba लंबा
dopahar दोपहर|hamesha हमेशा|bohot बहुत|boht बहुत|bahot बहुत|kaise कैसे|kaisi कैसी|kitni कितनी|kitne कितने|kaun कौन|kisko किसको|kisne किसने|unko उनको|inko इनको|tumko तुमको|humko हमको|hamko हमको|apko आपको|aapko आपको
mere मेरे|meri मेरी|tere तेरे|teri तेरी|tumhare तुम्हारे|tumhari तुम्हारी|humare हमारे|hamare हमारे|hamari हमारी|unka उनका|unki उनकी|inka इनका|iska इसका|iski इसकी|uska उसका|uski उसकी
wahan वहाँ|yahan यहाँ|kahin कहीं|kahan कहाँ|idhar इधर|udhar उधर|upar ऊपर|neeche नीचे|andar अंदर|bahar बाहर|saath साथ|pehle पहले|baad बाद|sabse सबसे|kuchh कुछ|koi कोई|kisi किसी'''
for ln in IRR.split('\n'):
    for it in ln.split('|'):
        it = it.strip()
        if it: k, v = it.split(' ', 1); put(k, v)
with open(out_p, 'w', encoding='utf8') as f:
    for k in sorted(out): f.write(k + '\t' + out[k] + '\n')
print('wrote', len(out), 'entries to', os.path.relpath(out_p, root))
