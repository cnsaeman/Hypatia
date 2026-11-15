package atlantis.hypatia.spell;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Downloads Hunspell dictionaries from LibreOffice's dictionary collection on GitHub
 * (github.com/LibreOffice/dictionaries) into the user dictionary folder, as LANG.aff and
 * LANG.dic, together with the dictionary's README, which states its licence.
 */
public final class DictionaryDownloader {

    private DictionaryDownloader() {}

    private static final String BASE = "https://raw.githubusercontent.com/LibreOffice/dictionaries/master/";

    /** A dictionary of the collection: language code, path without extension, README path or "". */
    public record Entry(String code, String path, String readme) {
        /** "English (United Kingdom) – en_GB". */
        public String label() {
            Locale l = Locale.forLanguageTag(code.replace('_', '-'));
            String name = l.getDisplayName(Locale.ENGLISH);
            return (name.isBlank() || name.equalsIgnoreCase(code) ? code : name) + " \u2013 " + code;
        }

        @Override public String toString() { return label(); }
    }

    /** The collection as of November 2026 (hyphenation and thesaurus files left out). */
    private static final String[] TABLE = {
            "af_ZA|af_ZA/af_ZA|af_ZA/README_af_ZA.txt",
            "an_ES|an_ES/an_ES|",
            "ar|ar/ar|ar/README_ar.txt",
            "as_IN|as_IN/as_IN|as_IN/README_as_IN.txt",
            "be_BY|be_BY/be-official|be_BY/README_be_BY.txt",
            "bg_BG|bg_BG/bg_BG|",
            "bn_BD|bn_BD/bn_BD|",
            "bo|bo/bo|bo/README.md",
            "br_FR|br_FR/br_FR|",
            "bs_BA|bs_BA/bs_BA|bs_BA/README.txt",
            "ca_valencia|ca/dictionaries/ca-valencia|",
            "ca|ca/dictionaries/ca|",
            "ckb|ckb/dictionaries/ckb|",
            "cs_CZ|cs_CZ/cs_CZ|cs_CZ/README_cs.txt",
            "da_DK|da_DK/da_DK|da_DK/README_da_DK.txt",
            "de_AT|de/de_AT_frami|",
            "de_CH|de/de_CH_frami|",
            "de_DE|de/de_DE_frami|de/README_de_DE_frami.txt",
            "el_GR|el_GR/el_GR|el_GR/README_el_GR.txt",
            "en_AU|en/en_AU|en/README_en_AU.txt",
            "en_CA|en/en_CA|en/README_en_CA.txt",
            "en_GB|en/en_GB|en/README_en_GB.txt",
            "en_US|en/en_US|en/README_en_US.txt",
            "en_ZA|en/en_ZA|en/README_en_ZA.txt",
            "eo|eo/eo|",
            "es_AR|es/es_AR|es/README_hunspell_es.txt",
            "es_BO|es/es_BO|es/README_hunspell_es.txt",
            "es_CL|es/es_CL|es/README_hunspell_es.txt",
            "es_CO|es/es_CO|es/README_hunspell_es.txt",
            "es_CR|es/es_CR|es/README_hunspell_es.txt",
            "es_CU|es/es_CU|es/README_hunspell_es.txt",
            "es_DO|es/es_DO|es/README_hunspell_es.txt",
            "es_EC|es/es_EC|es/README_hunspell_es.txt",
            "es_ES|es/es_ES|es/README_hunspell_es.txt",
            "es_GQ|es/es_GQ|es/README_hunspell_es.txt",
            "es_GT|es/es_GT|es/README_hunspell_es.txt",
            "es_HN|es/es_HN|es/README_hunspell_es.txt",
            "es_MX|es/es_MX|es/README_hunspell_es.txt",
            "es_NI|es/es_NI|es/README_hunspell_es.txt",
            "es_PA|es/es_PA|es/README_hunspell_es.txt",
            "es_PE|es/es_PE|es/README_hunspell_es.txt",
            "es_PH|es/es_PH|es/README_hunspell_es.txt",
            "es_PR|es/es_PR|es/README_hunspell_es.txt",
            "es_PY|es/es_PY|es/README_hunspell_es.txt",
            "es_SV|es/es_SV|es/README_hunspell_es.txt",
            "es_US|es/es_US|es/README_hunspell_es.txt",
            "es_UY|es/es_UY|es/README_hunspell_es.txt",
            "es_VE|es/es_VE|es/README_hunspell_es.txt",
            "et_EE|et_EE/et_EE|et_EE/README_et_EE.txt",
            "fa_IR|fa_IR/fa-IR|fa_IR/README_fa_IR.txt",
            "fr|fr_FR/dictionaries/fr|fr_FR/dictionaries/README_dict_fr.txt",
            "gd_GB|gd_GB/gd_GB|gd_GB/README_gd_GB.txt",
            "gl_ES|gl/gl_ES|",
            "gu_IN|gu_IN/gu_IN|gu_IN/README_gu_IN.txt",
            "gug|gug/gug|",
            "he_IL|he_IL/he_IL|he_IL/README_he_IL.txt",
            "hi_IN|hi_IN/hi_IN|hi_IN/README_hi_IN.txt",
            "hr_HR|hr_HR/hr_HR|hr_HR/README_hr_HR.txt",
            "hu_HU|hu_HU/hu_HU|hu_HU/README_hu_HU.txt",
            "id_ID|id/id_ID|",
            "is|is/is|is/license.txt",
            "it_IT|it_IT/it_IT|it_IT/README_it_IT.txt",
            "kmr_Latn|kmr_Latn/kmr_Latn|kmr_Latn/README_kmr_Latn.txt",
            "kn_IN|kn_IN/kn_IN|",
            "ko_KR|ko_KR/ko_KR|ko_KR/README_ko_KR.txt",
            "lij_IT|lij_IT/lij_IT|lij_IT/README_lij_IT.txt",
            "lo_LA|lo_LA/lo_LA|lo_LA/README_lo_LA.txt",
            "lt|lt_LT/lt|",
            "lv_LV|lv_LV/lv_LV|lv_LV/README_lv_LV.txt",
            "mn_MN|mn_MN/mn_MN|mn_MN/README_mn_MN.txt",
            "mr_IN|mr_IN/mr_IN|",
            "ne_NP|ne_NP/ne_NP|ne_NP/README_ne_NP.txt",
            "nl_NL|nl_NL/nl_NL|nl_NL/README.md",
            "nb_NO|no/nb_NO|",
            "nn_NO|no/nn_NO|",
            "oc_FR|oc_FR/oc_FR|oc_FR/README_oc_FR.txt",
            "or_IN|or_IN/or_IN|",
            "pa_IN|pa_IN/pa_IN|",
            "pl_PL|pl_PL/pl_PL|pl_PL/README_pl_PL.txt",
            "pt_BR|pt_BR/pt_BR|pt_BR/README_pt_BR.txt",
            "pt_PT|pt_PT/pt_PT|pt_PT/README_pt_PT.txt",
            "ro_RO|ro/ro_RO|",
            "ru_RU|ru_RU/ru_RU|ru_RU/README_ru_RU.txt",
            "sa_IN|sa_IN/sa_IN|",
            "si_LK|si_LK/si_LK|",
            "sk_SK|sk_SK/sk_SK|sk_SK/README_sk.txt",
            "sl_SI|sl_SI/sl_SI|sl_SI/README_sl_SI.txt",
            "sq_AL|sq_AL/sq_AL|sq_AL/README.txt",
            "sr_Latn|sr/sr-Latn|sr/README.txt",
            "sr|sr/sr|sr/README.txt",
            "sv_FI|sv_SE/dictionaries/sv_FI|",
            "sv_SE|sv_SE/dictionaries/sv_SE|",
            "sw_TZ|sw_TZ/sw_TZ|sw_TZ/README_sw_TZ.txt",
            "ta_IN|ta_IN/ta_IN|",
            "te_IN|te_IN/te_IN|te_IN/README_te_IN.txt",
            "th_TH|th_TH/th_TH|th_TH/README_th_TH.txt",
            "tr_TR|tr_TR/tr_TR|tr_TR/README.txt",
            "uk_UA|uk_UA/uk_UA|uk_UA/README_uk_UA.txt",
            "vi_VN|vi/vi_VN|"
    };

    /** All dictionaries, sorted by language name. */
    public static List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        for (String row : TABLE) {
            String[] p = row.split("\\|", -1);
            list.add(new Entry(p[0], p[1], p[2]));
        }
        list.sort(Comparator.comparing(Entry::label, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    /** The entry for a language ("en_GB", or "fr" also for "fr_FR"), or null. */
    public static Entry forLanguage(String language) {
        if (language == null) return null;
        String l = Dictionaries.normalize(language);
        List<Entry> all = entries();
        for (Entry e : all) if (e.code().equals(l)) return e;
        String base = l.contains("_") ? l.substring(0, l.indexOf('_')) : l;
        for (Entry e : all) if (e.code().equals(base)) return e;
        for (Entry e : all) if (e.code().startsWith(base + "_")) return e;
        return null;
    }

    /**
     * Downloads {@code e} into {@link Dictionaries#userDir()} (blocking; call off the EDT).
     * Returns the .dic file.
     */
    public static Path download(Entry e) throws IOException, InterruptedException {
        Path dir = Dictionaries.userDir();
        Files.createDirectories(dir);
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        Path aff = fetch(client, e.path() + ".aff", dir.resolve(e.code() + ".aff"));
        Path dic = fetch(client, e.path() + ".dic", dir.resolve(e.code() + ".dic"));
        if (!e.readme().isEmpty()) {
            try {
                fetch(client, e.readme(), dir.resolve("README_" + e.code() + ".txt"));
            } catch (IOException ignored) {
                // the dictionary itself is there
            }
        }
        Dictionaries.forget(e.code());
        return aff.resolveSibling(dic.getFileName());
    }

    private static Path fetch(HttpClient client, String path, Path target) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path.replace(" ", "%20")))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Hypatia LaTeX editor")
                .GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + BASE + path);
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }
}
