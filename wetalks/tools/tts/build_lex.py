import re, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from hinglish_lex import LEX
out={}
for line in LEX.strip().splitlines():
    if ':' not in line: continue
    words,dev=line.split(':',1); dev=dev.split('|')[0].strip()
    for w in words.split():
        if w.endswith('_') or not dev: continue
        out.setdefault(w.lower(),dev)
A=os.path.join(os.path.dirname(__file__),'..','..','app','src','main','assets','hinglish'); os.makedirs(A,exist_ok=True)
open(os.path.join(A,'hi_lex.tsv'),'w',encoding='utf-8').write('\n'.join(f"{k}\t{v}" for k,v in sorted(out.items()))+'\n')
# english words: the 30,000 most common English words (wordfreq), 3-14 letters, minus anything that is in the Hindi list
from wordfreq import top_n_list
words=[w for w in top_n_list('en', 40000) if re.fullmatch(r'[a-z]{3,14}', w) and w not in out][:30000]
open(os.path.join(A,'en_words.txt'),'w').write('\n'.join(sorted(set(words)))+'\n')
print(len(out),'hindi words,',len(set(words)),'english words')
