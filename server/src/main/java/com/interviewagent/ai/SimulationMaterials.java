package com.interviewagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SimulationMaterials {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public SimulationMaterials(JdbcClient jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    public JsonNode capture(String user,String packageId) {
        ObjectNode result=jdbc.sql("SELECT p.company,p.role,p.interview_round,j.content,r.parsed_text FROM interview_packages p LEFT JOIN job_descriptions j ON j.id=p.job_description_id AND j.user_id=:user LEFT JOIN resume_files r ON r.id=p.resume_file_id AND r.user_id=:user AND r.parsed_status='READY' WHERE p.id=:id AND p.user_id=:user")
            .param("id",packageId).param("user",user).query((rs,row)->json.createObjectNode().put("company",clip(rs.getString(1),200)).put("role",clip(rs.getString(2),200)).put("round",clip(rs.getString(3),200)).put("jd",clip(rs.getString(4),8000)).put("resume",clip(rs.getString(5),12000)))
            .optional().orElseThrow(()->new NoSuchElementException("资源不存在或无权访问。"));
        var cards=result.putArray("cards");
        var selected=jdbc.sql("SELECT c.project_name,c.project_description_and_responsibilities,c.project_highlights,c.technology_stack FROM interview_packages p JOIN interview_package_evidence_cards l ON l.interview_package_id=p.id JOIN project_evidence_cards c ON c.id=l.evidence_card_id AND c.user_id=:user WHERE p.id=:id AND p.user_id=:user ORDER BY c.id")
            .param("id",packageId).param("user",user).query((rs,row)->json.createObjectNode().put("projectName",clip(rs.getString(1),120)).put("projectDescriptionAndResponsibilities",clip(rs.getString(2),4000)).put("projectHighlights",clip(rs.getString(3),4000)).put("technologyStack",clip(rs.getString(4),4000))).list();
        if(selected.size()>30) throw new IllegalArgumentException("模拟面试包最多关联 30 张证据卡，请精简后开始。");
        int fieldLimit=selected.isEmpty()?4000:Math.min(4000,(10000/selected.size()-120)/3);
        for (ObjectNode card:selected) {
            // ponytail: share a fixed input budget across cards; keep every selected project name.
            for(String field:java.util.List.of("projectDescriptionAndResponsibilities","projectHighlights","technologyStack")) card.put(field,clip(card.path(field).asText(),fieldLimit));
            cards.add(card);
        }
        if(cards.toString().length()>16000) throw new IllegalArgumentException("证据卡内容过长，请精简后开始。");
        return result;
    }
    public JsonNode read(String snapshot,String user,String packageId) {
        if (snapshot==null) return capture(user,packageId); // Historical sessions only; new sessions always persist a snapshot.
        try { return json.readTree(snapshot); } catch (Exception error) { throw new SimulationException("INVALID_REQUEST"); }
    }
    private static String clip(String text,int limit) { return text==null||text.isBlank()?"待补充":text.substring(0,Math.min(text.length(),limit)); }
}
