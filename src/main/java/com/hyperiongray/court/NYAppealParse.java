package com.hyperiongray.court;

import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by mark on 5/20/15.
 */
public class NYAppealParse {
    private static final Logger logger = LoggerFactory.getLogger(NYAppealParse.class);
    private static Options options;
    private enum KEYS {File, Casenumber, DocumentLength, Court, County, Judge, Keywords, FirstDate, AppealDate, Gap_days,
        ModeOfConviction, Crimes, Judges, Defense, DefendantAppellant, DefendantRespondent, DistrictAttorney,
        HarmlessError, NotHarmlessError };

    public static String extracts[][] = {
            {KEYS.File.toString(), ""},
            {KEYS.Casenumber.toString(), "\\[.+\\]"},
            {KEYS.DocumentLength.toString(), "regex"},
            {KEYS.Court.toString(), "regex"},
            {KEYS.County.toString(), "[a-zA-Z]+\\sCounty"},
            {KEYS.Judge.toString(), "Court \\(.+\\)"},
            {KEYS.Keywords.toString(), "regex"},
            {KEYS.FirstDate.toString(), "rendered.+\\."},
            {KEYS.AppealDate.toString(), "(January|February|March|April|May|June|July|August|September|October|November|December).+"},
            {KEYS.Gap_days.toString(), "regex"},
            {KEYS.ModeOfConviction.toString(), "plea\\s*of\\s*guilty|jury\\s*verdict|nonjury\\s*trial"},
            {KEYS.Crimes.toString(), "regex"},
            {KEYS.Judges.toString(), "Present.+"},
            {KEYS.Defense.toString(), "regex"},
            {KEYS.DefendantAppellant.toString(), "petitioners-plaintiffs-appellants"},
            {KEYS.DefendantRespondent.toString(), "respondents-defendants-respondents"},
            {KEYS.DistrictAttorney.toString(), "\\n[a-zA-Z,.\\s]+District Attorney"},
            {KEYS.HarmlessError.toString(), "regex"},
            {KEYS.NotHarmlessError.toString(), "regex"}
    };

    private String inputDir;
    private String outputFile;
    private int breakSize = 10000;

    private static SimpleDateFormat dateFormat = new SimpleDateFormat("MMMMMdd,yyyy");

    public String toString() {
        StringBuffer buffer = new StringBuffer("Extractor\nField:Regex\n");
        for (int row = 0; row < extracts.length; ++row) {
            buffer.append(extracts[row][0]).append(":").append(extracts[row][1]).append("\n");
        }
        return buffer.toString();
    }

    public Map<String, String> extractInfo(File file) throws IOException {
        String text = FileUtils.readFileToString(file);
        Map<String, String> info = new HashMap<>();
        for (int e = 0; e < extracts.length; ++e) {
            String key = extracts[e][0];
            // skip the keys extracted with regex
            if (key.equals(KEYS.File.toString())) continue;
            if (key.equals(KEYS.DocumentLength.toString())) continue;
            if (key.equals(KEYS.Court.toString())) continue;
            if (key.equals(KEYS.Gap_days.toString())) continue;
            String regex = extracts[e][1];
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String value = m.group();
                if (key.equals(KEYS.DistrictAttorney.toString())) value = value.substring(2);
                if (key.equals(KEYS.Judges.toString())) value = value.substring("Judges-".length() + 1);
                if (key.equals(KEYS.Judge.toString())) value = value.substring("Judge ".length() + 1, value.length() - 1);
                if (key.equals(KEYS.FirstDate.toString())) value = value.substring("rendered ".length(), value.length() - 1);
                info.put(key, value);
            }
        }
        info.put(KEYS.File.toString(), file.getName());
        info.put(KEYS.DocumentLength.toString(), Integer.toString(text.length()));
        String court = text.contains("Supreme Court") ? "Supreme Court" : "County Court";
        info.put(KEYS.Court.toString(), court);
        if (info.containsKey(KEYS.FirstDate.toString()) && info.containsKey(KEYS.AppealDate.toString())) {
            String firstDateStr = info.get(KEYS.FirstDate.toString());
            String appealDateStr = info.get(KEYS.AppealDate.toString());
            try {
                Date firstDate = dateFormat.parse(firstDateStr);
                Date appealDate = dateFormat.parse(appealDateStr);
                long gap = appealDate.getTime() - firstDate.getTime();
                int gapDays = (int) (gap / 1000 / 60 / 60 / 24);
                if (gapDays > 0) {
                    info.put(KEYS.Gap_days.toString(), Integer.toString(gapDays));
                }
            } catch (NumberFormatException | ParseException e) {
                logger.error("Parsing error for {} and/or {}", firstDateStr, appealDateStr);
            }
        }
        if (info.containsKey(KEYS.ModeOfConviction.toString())) {
            String mode = info.get(KEYS.ModeOfConviction.toString());
            // crime is from here till the end of the line
            int crimeStart = text.indexOf(mode);
            if (crimeStart > 0) {
                crimeStart += mode.length();
                int comma = text.indexOf("\n", crimeStart);
                if (comma > 0 && (comma - crimeStart < 5)) crimeStart += (comma - crimeStart + 1);
                int crimeEnd = text.indexOf(".", crimeStart);
                if (crimeEnd > 0) {
                    info.put(KEYS.Crimes.toString(), text.substring(crimeStart, crimeEnd));
                }
            }
        }
        if (info.containsKey(KEYS.DefendantAppellant.toString())) {
            // the answer is in the previous line
            String value = info.get(KEYS.DefendantAppellant.toString());
            int valueInd = text.indexOf(value);
            if (valueInd > 0) {
                int endLine = text.lastIndexOf("\n", valueInd);
                if (endLine > 0) {
                    int startLine = text.lastIndexOf("\n", endLine - 1);
                    if (startLine > 0) {
                        value = text.substring(startLine + 1, endLine);
                        info.put(KEYS.DefendantAppellant.toString(), value);
                    }
                }
            }
        }
        if (info.containsKey(KEYS.DefendantRespondent.toString())) {
            // the answer is in the previous line
            String value = info.get(KEYS.DefendantRespondent.toString());
            int valueInd = text.indexOf(value);
            if (valueInd > 0) {
                int endLine = text.lastIndexOf("\n", valueInd);
                if (endLine > 0) {
                    int startLine = text.lastIndexOf("\n", endLine - 1);
                    if (startLine > 0) {
                        value = text.substring(startLine + 1, endLine);
                        info.put(KEYS.DefendantRespondent.toString(), value);
                    }
                }
            }
        }
        return info;
    }

    public static void main(String[] args) {
        formOptions();
        if (args.length == 0) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("NYAppealParse - extract legal information from court cases", options);
            return;
        }
        NYAppealParse instance = new NYAppealParse();
        try {
            if (!instance.parseParameters(args)) {
                return;
            }
            instance.parseDocuments();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void formOptions() {
        options = new Options();
        options.addOption("i", "inputDir", true, "Input directory");
        options.addOption("o", "outputFile", true, "Output file, .csv will be added");
        options.addOption("b", "breakSize", true, "Output file size in lines");
    }

    private void parseDocuments() throws IOException {
        int lineCount = 0;
        int fileNumber = 0;
        StringBuffer buf = new StringBuffer();
        for (int e = 0; e < extracts.length; ++e) {
            String key = extracts[e][0];
            buf.append(key).append("|");
        }
        buf.deleteCharAt(buf.length() - 1);
        buf.append("\n");
        FileUtils.write(new File(outputFile + fileNumber + ".csv"), buf.toString(), false);
        File[] files = new File(inputDir).listFiles();
        Arrays.sort(files);
        for (File file : files) {
            buf = new StringBuffer();
            Map<String, String> answer = extractInfo(file);
            for (int e = 0; e < extracts.length; ++e) {
                String key = extracts[e][0];
                String value = "";
                if (answer.containsKey(key)) {
                    value = answer.get(key);
                }
                buf.append(value).append("|");
            }
            buf.deleteCharAt(buf.length() - 1);
            buf.append("\n");
            FileUtils.write(new File(outputFile + fileNumber + ".csv"), buf.toString(), true);
            ++lineCount;
            if (lineCount >= breakSize) {
                ++fileNumber;
                lineCount = 1;
            }
            buf = new StringBuffer();
            for (int e = 0; e < extracts.length; ++e) {
                String key = extracts[e][0];
                buf.append(key).append("|");
            }
        }
    }

    private boolean parseParameters(String[] args) throws org.apache.commons.cli.ParseException {
        CommandLineParser parser = new GnuParser();
        CommandLine cmd = parser.parse(options, args);
        inputDir = cmd.getOptionValue("inputDir");
        outputFile = cmd.getOptionValue("outputFile");
        if (cmd.hasOption("breakSize")) {
            breakSize = Integer.parseInt(cmd.getOptionValue("breakSize"));
        }
        return true;
    }

}

