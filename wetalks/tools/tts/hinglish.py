"""Prototype of the Hinglish -> Devanagari converter (the Kotlin version in the app follows the same steps)."""
import re, os
A = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'assets', 'hinglish')
LEX = dict(l.rstrip('\n').split('\t') for l in open(os.path.join(A, 'hi_lex.tsv'), encoding='utf-8') if '\t' in l)
EN = set(l.strip() for l in open(os.path.join(A, 'en_words.txt')) if l.strip())

CONS = [('chh','छ'),('ksh','क्ष'),('kh','ख'),('gh','घ'),('ch','च'),('jh','झ'),('th','थ'),('dh','ध'),('ph','फ'),('bh','भ'),('sh','श'),
        ('k','क'),('g','ग'),('c','क'),('j','ज'),('t','त'),('d','द'),('n','न'),('p','प'),('f','फ'),('b','ब'),('m','म'),('y','य'),('r','र'),
        ('l','ल'),('v','व'),('w','व'),('s','स'),('h','ह'),('z','ज़'),('x','क्स'),('q','क')]
VOW = [('aa','आ','ा'),('ee','ई','ी'),('ii','ई','ी'),('oo','ऊ','ू'),('uu','ऊ','ू'),('ai','ऐ','ै'),('au','औ','ौ'),
       ('a','अ',''),('i','इ','ि'),('u','उ','ु'),('e','ए','े'),('o','ओ','ो')]
HALANT = '्'

def translit(w):
    toks = []; i = 0
    while i < len(w):
        for k, d in CONS:
            if w.startswith(k, i): toks.append(('C', d)); i += len(k); break
        else:
            for k, ind, mat in VOW:
                if w.startswith(k, i): toks.append(('V', ind, mat, k)); i += len(k); break
            else: i += 1    # unknown letter (apostrophe etc.)
    out = []; n = len(toks); j = 0
    while j < n:
        t = toks[j]
        if t[0] == 'V':
            ind, k = t[1], t[3]
            out.append(ind); j += 1
            continue
        nxt = toks[j + 1] if j + 1 < n else None
        # n / m inside a word before another consonant is the nasal dot (pahunch -> पहुंच, ghanta -> घंटा)
        if t[1] in ('न', 'म') and j > 0 and nxt is not None and nxt[0] == 'C' and nxt[1] not in ('न', 'म', 'य', 'ह'):
            out.append('ं'); j += 1; continue
        out.append(t[1])
        if nxt is None: pass
        elif nxt[0] == 'V':
            mat, k = nxt[2], nxt[3]
            last = (j + 2 == n)
            if k == 'a' and last and j > 0: mat = 'ा'            # final -a is long
            elif k == 'i' and last: mat = 'ी'                    # final -i is long
            out.append(mat); j += 1
        else:
            # verb endings (kar-na, chal-ti, reh-te): the sound after the stem is kept, so no conjunct
            ve = j + 3 == n and toks[j + 2][0] == 'V' and toks[j + 2][3] in ('a', 'i', 'e') and nxt[1] in ('त', 'न', 'ग')
            if not ve: out.append(HALANT)
        j += 1
    return ''.join(out)

def convert_word(w):
    lw = w.lower()
    if len(lw) < 2: return w
    if lw in LEX: return LEX[lw]
    if lw in EN: return w
    return translit(lw)

def to_devanagari(text):
    return re.sub(r"[A-Za-z']+", lambda m: convert_word(m.group(0).replace("'", '')), text)

# the reader only converts when the text really is romanised Hindi (several Hindi words), never plain English
def looks_hinglish(text):
    ws = [w.lower() for w in re.findall(r"[A-Za-z]+", text)]
    if len(ws) < 3: return False
    hits = sum(1 for w in ws if w in LEX and w not in ('to', 'do', 'ho', 'par', 'kar', 'ab', 'main', 'se', 'ke', 'ki', 'ka', 'ko', 'ne', 'pe', 'na', 'ya', 'mat', 'bas', 'tab', 'sab', 'din', 'kam', 'bhi', 'toh', 'aur', 'ek'))
    soft = sum(1 for w in ws if w in LEX) - hits
    return hits >= 2 or (hits >= 1 and hits + soft >= 4) or (hits + soft) * 100 // len(ws) >= 45

if __name__ == '__main__':
    for t in ["ab tum dekhna bhai, main apne application ke andar aise feature dal dunga, ki logon ko majbur hokar idhar aana padega.",
              "yaar tu kal kya kar raha tha, mujhe office jaana hai aur tumhe bhi aana hai",
              "Mera presentation tomorrow hai aur main nervous hoon",
              "kal mujhe office jaana hai", "Hello how are you doing today my friend", "bhai kal milte hain, tension mat lo sab theek ho jayega"]:
        print(looks_hinglish(t), '|', to_devanagari(t))
