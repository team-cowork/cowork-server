-- Deploy with old roadmap writers stopped. GLOBAL roadmaps cannot own team/project assignments.
-- Remove only assignments linked to GLOBAL content; keep roadmaps, nodes and custom-roadmap assignments.
DELETE assignment
FROM tb_roadmap_assignments assignment
JOIN tb_roadmaps roadmap ON roadmap.id = assignment.roadmap_id
WHERE roadmap.scope = 'GLOBAL';
