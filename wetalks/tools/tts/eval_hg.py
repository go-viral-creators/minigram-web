import sys, re, time
sys.path.insert(0, '/home/user/minigram-web/wetalks/tools/tts')
import importlib, hinglish; importlib.reload(hinglish)
import sherpa_onnx, soundfile as sf
from scipy.signal import resample_poly
from math import gcd
from faster_whisper import WhisperModel
d="/tmp/claude-0/st3/"
m=sherpa_onnx.OfflineTtsSupertonicModelConfig(duration_predictor=d+"duration_predictor.int8.onnx",text_encoder=d+"text_encoder.int8.onnx",vector_estimator=d+"vector_estimator.int8.onnx",vocoder=d+"vocoder.int8.onnx",tts_json=d+"tts.json",unicode_indexer=d+"unicode_indexer.bin",voice_style=d+"voice.bin")
tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(supertonic=m,num_threads=3,provider="cpu")))
w=WhisperModel("small",device="cpu",compute_type="int8")
def load(x,sr):
    g=gcd(16000,sr); return resample_poly(x,16000//g,sr//g).astype("float32")
def norm(s): return re.sub(r"[^ऀ-ॿa-z ]","",s.lower().replace("ड़","ड").replace("ढ़","ढ").replace("़","")).split()
def wer(ref,hyp):
    r,h=norm(ref),norm(hyp); import difflib
    sm=difflib.SequenceMatcher(None,r,h); match=sum(b.size for b in sm.get_matching_blocks()); return 1-match/max(1,len(r))
T=[("ab tum dekhna bhai main apne application ke andar aise feature dal dunga ki logon ko majbur hokar idhar aana padega","अब तुम देखना भाई मैं अपने application के अंदर ऐसे feature डाल दूँगा कि लोगों को मजबूर होकर इधर आना पड़ेगा"),
("yaar tu kal kya kar raha tha mujhe office jaana hai aur tumhe bhi aana hai","यार तू कल क्या कर रहा था मुझे office जाना है और तुम्हें भी आना है"),
("bhai kal milte hain tension mat lo sab theek ho jayega","भाई कल मिलते हैं tension मत लो सब ठीक हो जाएगा"),
("mujhe nahi pata wo kab aayega lekin jab bhi aaye mujhe bata dena","मुझे नहीं पता वो कब आएगा लेकिन जब भी आए मुझे बता देना"),
("aaj ka match bahut accha tha tumne dekha kya","आज का match बहुत अच्छा था तुमने देखा क्या"),
("kya haal hai bhai kaisa chal raha hai sab kuch","क्या हाल है भाई कैसा चल रहा है सब कुछ"),
("main ghar pahunch gaya hoon thodi der mein call karta hoon","मैं घर पहुँच गया हूँ थोड़ी देर में call करता हूँ"),
("tumhara kaam khatam hua ya nahi abhi tak","तुम्हारा काम ख़त्म हुआ या नहीं अभी तक")]
tot={"raw":0,"conv":0}
for roman,ref in T:
    for mode,txt in (("raw",roman),("conv",hinglish.to_devanagari(roman))):
        g=sherpa_onnx.GenerationConfig(); g.sid=0; g.extra={"lang":"hi"}
        a=tts.generate(txt,g); hyp=" ".join(s.text for s in w.transcribe(load(a.samples,a.sample_rate),language="hi",beam_size=3)[0])
        e=wer(ref,hyp); tot[mode]+=e
        if mode=="conv": print("  conv:",txt)
        print(f"{mode:5s} WER {e:.2f} | {hyp}")
print({k:round(v/len(T),3) for k,v in tot.items()})
