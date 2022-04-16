
import javax.swing.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import com.alibaba.fastjson.JSONObject;
import org.w3c.dom.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Xml2Json {
    private final static double SECTION_LEFT = 150.0;
    private final static double SECTION_RIGHT = 200.0;
    private final static double LINE_DISTANCE = 10.0;
    private final static int MAX_SECTION_LENGTH = 8;
    private final static int MAX_SUBSECTION_LENGTH = 7;
    private final static String PATTERN_SECTION="^(\\d\\.\\d+)\\s+(.*)";
    private final static String PATTERN_COMMENT="^(\\d+)\\s+([^指：]+)[指：](.+)";
    private final static String PATTERN_DISEASE_SKIP="^(第\\s\\d+\\s类：)$";
    private final static String PATTERN_DISEASE_SECTION="^(与.+相关的疾病)$";
    private final static String PATTERN_DISEASE_SUBSECTION="^(\\d+)\\s+(.+)";
    private final static String PATTERN_SPACE_IN_TEXT="(\\S+)(\\s+)(\\S+)";


    private static void processSingleFile(String inputFileName, String outputFileName){
        try {
            // 1. read xml file
            File file = new File(inputFileName);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            doc.getDocumentElement().normalize();

            // 2. process xml content
            Node rootNode = doc.getDocumentElement();
            NodeList pagesNodeList = rootNode.getChildNodes();
            JSONObject res = new JSONObject();
            String currentSection = "";
            Pattern patternSection = Pattern.compile(PATTERN_SECTION);
            Pattern patternComment = Pattern.compile(PATTERN_COMMENT);
            Pattern patternDiseaseSkip = Pattern.compile(PATTERN_DISEASE_SKIP);
            Pattern patternDiseaseSection= Pattern.compile(PATTERN_DISEASE_SECTION);
            Pattern patternDiseaseSubsection= Pattern.compile(PATTERN_DISEASE_SUBSECTION);
            // 2.1 iterate over pages
            for (int i_page = 0, len_page = pagesNodeList.getLength(); i_page < len_page; i_page++) {
                if (i_page == 1) continue;
                // 2.2 get content of each line
                Element elementPage = null;
                try {
                    elementPage = (Element) pagesNodeList.item(i_page);
                } catch (ClassCastException e) {
                    continue;
                }
                String bboxPage = elementPage.getAttribute("bbox");
                double lowerPage = Double.valueOf(bboxPage.split(",")[1]);
                int lineCount = 0;
                List<Double> lowerSpecial = new ArrayList<>();
                String currentSectionComment = "";
                boolean inComment = false;
                List<JSONObject> keys = new ArrayList<JSONObject>();
                List<JSONObject> contents = new ArrayList<JSONObject>();
                NodeList pageNodeList = elementPage.getElementsByTagName("textbox");
                for (int i_textbox = 0, len_line = pageNodeList.getLength(); i_textbox < len_line; i_textbox++) {
                    // Layer 1: TextBox （单个文本块）
                    Element elementTextBox = (Element) pageNodeList.item(i_textbox);
                    NodeList textBoxNodeList = elementTextBox.getElementsByTagName("textline");
                    // Layer 2: TextLine （单行）
                    for (int i_textline = 0, len_textbox = textBoxNodeList.getLength(); i_textline < len_textbox; i_textline++) {
                        Element elementTextLine = (Element) textBoxNodeList.item(i_textline);
                        JSONObject jsonObject = new JSONObject();
                        String textLine = "";
                        String bbox = elementTextLine.getAttribute("bbox");
                        String[] coordinates = bbox.split(",");
                        jsonObject.put("left", Double.valueOf(coordinates[0]));
                        jsonObject.put("right", Double.valueOf(coordinates[2]));
                        jsonObject.put("upper", Double.valueOf(coordinates[3]));
                        jsonObject.put("lower", Double.valueOf(coordinates[1]));
                        NodeList textLineNodeList = elementTextLine.getElementsByTagName("text");
                        // Layer 3: Text (单个字)
                        for (int i_text = 0, len_textline = textLineNodeList.getLength(); i_text < len_textline; i_text++) {
                            Node node = textLineNodeList.item(i_text);
                            textLine += node.getTextContent();
                        }
                        lineCount += 1;
                        textLine = textLine.trim();

                        if (!textLine.isEmpty()) {
                            // 特殊情况1: 大标题需跳过
                            if(textLine.startsWith("这部分讲的是") || coordinates[0].equals("60.000")) {
                                if(textLine.startsWith("这部分讲的是")) lowerSpecial.add(Double.valueOf(coordinates[1]));
                                continue;
                            }
                            // 特殊情况2: 首页，前几行需跳过
                            if(i_page == 3 && Double.valueOf(coordinates[1]) > 600) {
                                if(Double.valueOf(coordinates[3]) < 715 && Double.valueOf(coordinates[1]) > 698) res.put("产品名称", textLine);
                                continue;
                            }
                            // 特殊情况3: 重疾险中，"第 1 类："及类似，需跳过
                            if(patternDiseaseSkip.matcher(textLine).find()) continue;

                            // 属于section/subsection/comment
                            if (jsonObject.getDouble("left") < SECTION_LEFT || patternDiseaseSection.matcher(textLine).find()) {
                                // 特殊情况4: 大标题换行，需跳过
                                boolean toContinue=false;
                                for(double l: lowerSpecial){
                                     if (l - jsonObject.getDouble("upper") < LINE_DISTANCE && l - jsonObject.getDouble("upper") > 0) {
                                         toContinue=true;
                                         break;
                                     }
                                }
                                if(toContinue) continue;
                                jsonObject.put("content", textLine);
                                keys.add(jsonObject);
                            }
                            // 属于正文
                            else {
                                jsonObject.put("content", textLine);
                                contents.add(jsonObject);
                            }
                        }
//                        System.out.println(String.format("%d page, %d line, (%f, %f, %f, %f): %s", i_page%2, lineCount, left, lower, right, upper, textLine));
                    }
                }
                // 2.3 sort keys and contents
                Collections.sort(keys, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject t1, JSONObject t2) {
                        return (int) (t2.getDouble("upper")  - t1.getDouble("upper"));
                    }
                });
                Collections.sort(contents, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject t1, JSONObject t2) {
                        return (int) (t2.getDouble("upper")  - t1.getDouble("upper"));
                    }
                });
                // 2.4 update res by iterating keys
                int i_key = 0;
                int i_content = 0;
                while(i_key < keys.size()){
                    JSONObject key = keys.get(i_key);
                    String section = "";
                    String subsection = null;
                    String contentString = key.getString("content");
                    String keyString = "";
                    Matcher matcher = patternComment.matcher(contentString);
                    // 注释section
                    if(inComment){
                        // 新注释
                        if(matcher.find()){
                            contentString = matcher.group(3);
                            keyString = matcher.group(2);
                            currentSectionComment = "注释-" + keyString;
                            res.getJSONObject("注释").put(keyString, contentString);
                        }
                        // 已有注释
                        else{
                            keyString = currentSectionComment.split("-")[1];
                            res.getJSONObject("注释").put(keyString, res.getJSONObject("注释").get(keyString) + contentString);
                        }
                    }
                    // 其他section
                    else {
                        //注释section
                        if(matcher.find()){
                            contentString = matcher.group(3);
                            keyString = matcher.group(2);
                            currentSectionComment = "注释-" + keyString;
                            if(!res.containsKey("注释")) res.put("注释", new JSONObject());
                            res.getJSONObject("注释").put(keyString, contentString);
                            inComment = true;
                        }
                        // 其他section
                        else{
                            String finalKeyString = "";
                            double upperCurrentBox = key.getDouble("upper");
                            double upperNextBox;
                            matcher = patternSection.matcher(contentString);
                            Matcher matcher1 = patternDiseaseSection.matcher(contentString);
                            if(i_key + 1 < keys.size()) upperNextBox = keys.get(i_key+1).getDouble("upper");
                            else upperNextBox = lowerPage;
                            // 主section
                            if (matcher.find() || matcher1.find()) {
                                boolean isDieaseaSection = false;
                                try {
                                    keyString = matcher.group(2);
                                }
                                catch (Exception e) {
                                    keyString = contentString;
                                    isDieaseaSection = true;
                                }
                                // 特殊情况1：section与正文连在一起
                                if(keyString.length() > MAX_SECTION_LENGTH+1 && !isDieaseaSection){
                                    contentString = keyString.substring(MAX_SECTION_LENGTH+1).trim();
                                    keyString = keyString.substring(0, MAX_SECTION_LENGTH+1).trim();
                                }
                                else contentString = "";
                                finalKeyString = keyString;
                                // 特殊情况2：keyString换行
                                while (keyString.length() >= MAX_SECTION_LENGTH && i_key + 1 < keys.size() && key.getDouble("lower")-keys.get(i_key+1).getDouble("upper") < LINE_DISTANCE) {
                                    i_key += 1;
                                    key = keys.get(i_key);
                                    upperNextBox = key.getDouble("upper");
                                    keyString = key.getString("content");
                                    finalKeyString += keyString;
                                }
                                section = finalKeyString;
                                if(!isDieaseaSection) subsection = "general";
                                if(!res.containsKey(section)) res.put(section, new JSONObject());
                                if(subsection != null && !res.getJSONObject(section).containsKey("general"))res.getJSONObject(section).put("general", contentString);
                            }
                            // subsection
                            else {
                                // 特殊情况1: 疾病类subsection
                                matcher = patternDiseaseSubsection.matcher(contentString);
                                if (matcher.find()){
                                    contentString = matcher.group(2);
                                }
                                // 特殊情况2: subsection与正文连在一起
                                if(contentString.length() > MAX_SUBSECTION_LENGTH+1){
                                    keyString = contentString.substring(0, MAX_SUBSECTION_LENGTH+1).trim();
                                    contentString = contentString.substring(MAX_SUBSECTION_LENGTH+1).trim();
                                }
                                else {
                                    keyString = contentString;
                                    contentString = "";
                                }
                                finalKeyString = keyString;
                                // 特殊情况2：keyString换行
                                while (keyString.length() >= MAX_SUBSECTION_LENGTH && i_key + 1 < keys.size() && key.getDouble("lower")-keys.get(i_key+1).getDouble("upper") < LINE_DISTANCE) {
                                    i_key += 1;
                                    key = keys.get(i_key);
                                    upperNextBox = key.getDouble("upper");
                                    keyString = key.getString("content");
                                    finalKeyString += keyString;
                                }
                                section = currentSection.split("-")[0];
                                subsection = finalKeyString.replaceAll(PATTERN_SPACE_IN_TEXT, "$1$3");
                                if (!res.getJSONObject(section).containsKey(subsection)) res.getJSONObject(section).put(subsection, contentString);
                            }
                            // 2.4.1 get content of previous section/subsection (if necessary)
                            if(!currentSection.isEmpty()) {
                                String sections[] = currentSection.split("-");
                                while (i_content < contents.size() && contents.get(i_content).getDouble("lower") > upperCurrentBox) {
                                    res.getJSONObject(sections[0]).put(sections[1], res.getJSONObject(sections[0]).getString(sections[1]) + contents.get(i_content).getString("content"));
                                    i_content += 1;
                                }
                            }
                            // 2.4.2 get content of current section/subsection
                            currentSection = section + "-" + subsection;
                            while(i_content < contents.size() && contents.get(i_content).getDouble("lower") > upperNextBox){
                                res.getJSONObject(section).put(subsection, res.getJSONObject(section).getString(subsection) + contents.get(i_content).getString("content"));
                                i_content += 1;
                            }
                        }
                    }
                    i_key += 1;
                }
                // sanity check
                assert i_content == contents.size();
            }

            // 3. write json file
            try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(new File(outputFileName)))) {
                outputStreamWriter.write(res.toJSONString());
                outputStreamWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static void main(String argv[]) {
        String inputFileName = "/Users/zeyesun/Downloads/getPlanClausePdf-2.xml";
        String outputFileName = "/Users/zeyesun/Downloads/getPlanClausePdf-2.json";

        processSingleFile(inputFileName, outputFileName);
    }
}