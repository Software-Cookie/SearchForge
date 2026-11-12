package dev.searchforge.indexer.analysis;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisTest {

    // Pairs from Porter's paper and his published sample vocabulary.
    @ParameterizedTest
    @CsvSource({
        "caresses,caress", "ponies,poni", "ties,ti", "caress,caress", "cats,cat",
        "feed,feed", "agreed,agre", "plastered,plaster", "motoring,motor", "sing,sing",
        "conflated,conflat", "troubled,troubl", "sized,size", "hopping,hop", "tanned,tan",
        "falling,fall", "hissing,hiss", "fizzed,fizz", "failing,fail", "filing,file",
        "happy,happi", "sky,sky", "relational,relat", "conditional,condit",
        "rational,ration", "valenci,valenc", "hesitanci,hesit", "digitizer,digit",
        "conformabli,conform", "radicalli,radic", "differentli,differ", "vileli,vile",
        "analogousli,analog", "vietnamization,vietnam", "predication,predic",
        "operator,oper", "feudalism,feudal", "decisiveness,decis", "hopefulness,hope",
        "callousness,callous", "formaliti,formal", "sensitiviti,sensit",
        "sensibiliti,sensibl", "triplicate,triplic", "formative,form", "formalize,formal",
        "electriciti,electr", "electrical,electr", "hopeful,hope", "goodness,good",
        "revival,reviv", "allowance,allow", "inference,infer", "airliner,airlin",
        "gyroscopic,gyroscop", "adjustable,adjust", "defensible,defens", "irritant,irrit",
        "replacement,replac", "adjustment,adjust", "dependent,depend", "adoption,adopt",
        "homologou,homolog", "communism,commun", "activate,activ", "angulariti,angular",
        "homologous,homolog", "effective,effect", "bowdlerize,bowdler", "probate,probat",
        "rate,rate", "cease,ceas", "controll,control", "roll,roll",
        "generalizations,gener", "oscillators,oscil", "connection,connect",
        "connecting,connect", "running,run", "archaeology,archaeolog", "is,is", "a,a"
    })
    void stemsLikePorter(String word, String stem) {
        assertThat(PorterStemmer.stem(word)).isEqualTo(stem);
    }

    @Test
    void leavesNonAsciiAndDigitsAlone() {
        assertThat(PorterStemmer.stem("café")).isEqualTo("café");
        assertThat(PorterStemmer.stem("java21")).isEqualTo("java21");
    }

    @Test
    void tokenizesUnicodeWithOffsetsAndPositions() {
        List<Analyzer.Token> t = Analyzer.tokenize("Hello, Wörld! 東京 x2");
        assertThat(t).extracting(Analyzer.Token::surface)
                .containsExactly("hello", "wörld", "東京", "x2");
        assertThat(t.get(1).start()).isEqualTo(7);
        assertThat(t.get(1).end()).isEqualTo(12);
        assertThat(t.get(3).position()).isEqualTo(3);
        assertThat(Analyzer.tokenize(null)).isEmpty();
        assertThat(Analyzer.tokenize("x".repeat(41) + " ok")).extracting(Analyzer.Token::surface)
                .containsExactly("ok");
        assertThat(Analyzer.tokenize("emoji 😀 tail 𝔘")).extracting(Analyzer.Token::surface)
                .containsExactly("emoji", "tail", "𝔘");
    }

    @Test
    void termsDropStopwordsButKeepPositionGaps() {
        List<Analyzer.Term> terms = Analyzer.terms("The running of the bulls", 10);
        assertThat(terms).containsExactly(new Analyzer.Term("run", 11),
                new Analyzer.Term("bull", 14));
        assertThat(Analyzer.termOf("the")).isNull();
        assertThat(Analyzer.isStopWord("and")).isTrue();
    }
}
