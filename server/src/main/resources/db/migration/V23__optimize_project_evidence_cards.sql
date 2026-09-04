ALTER TABLE project_evidence_cards ADD COLUMN technology_stack TEXT NOT NULL DEFAULT '待补充';
ALTER TABLE project_evidence_cards ADD COLUMN project_description_and_responsibilities TEXT NOT NULL DEFAULT '待补充';
ALTER TABLE project_evidence_cards ADD COLUMN project_highlights TEXT NOT NULL DEFAULT '待补充';

UPDATE project_evidence_cards
SET project_description_and_responsibilities = CASE
        WHEN NULLIF(TRIM(background_and_role), '') IS NOT NULL
             AND NULLIF(TRIM(personal_contribution), '') IS NOT NULL
            THEN TRIM(background_and_role) || '；' || TRIM(personal_contribution)
        ELSE COALESCE(NULLIF(TRIM(background_and_role), ''), NULLIF(TRIM(personal_contribution), ''), '待补充')
    END,
    project_highlights = CASE
        WHEN NULLIF(TRIM(goal_and_metrics), '') IS NOT NULL
             AND NULLIF(TRIM(constraints_and_tradeoffs), '') IS NOT NULL
             AND NULLIF(TRIM(result_and_retrospective), '') IS NOT NULL
            THEN TRIM(goal_and_metrics) || '；' || TRIM(constraints_and_tradeoffs) || '；' || TRIM(result_and_retrospective)
        ELSE TRIM(BOTH '；' FROM
            CASE WHEN NULLIF(TRIM(goal_and_metrics), '') IS NOT NULL THEN TRIM(goal_and_metrics) || '；' ELSE '' END ||
            CASE WHEN NULLIF(TRIM(constraints_and_tradeoffs), '') IS NOT NULL THEN TRIM(constraints_and_tradeoffs) || '；' ELSE '' END ||
            CASE WHEN NULLIF(TRIM(result_and_retrospective), '') IS NOT NULL THEN TRIM(result_and_retrospective) ELSE '' END
        )
    END;

UPDATE project_evidence_cards
SET project_highlights = '待补充'
WHERE NULLIF(TRIM(project_highlights), '') IS NULL;

ALTER TABLE project_evidence_cards ALTER COLUMN background_and_role SET DEFAULT '';
ALTER TABLE project_evidence_cards ALTER COLUMN goal_and_metrics SET DEFAULT '';
ALTER TABLE project_evidence_cards ALTER COLUMN constraints_and_tradeoffs SET DEFAULT '';
ALTER TABLE project_evidence_cards ALTER COLUMN personal_contribution SET DEFAULT '';
ALTER TABLE project_evidence_cards ALTER COLUMN result_and_retrospective SET DEFAULT '';
ALTER TABLE project_evidence_cards ALTER COLUMN applicable_question_types SET DEFAULT '';
