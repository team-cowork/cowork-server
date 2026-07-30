import { XMLParser } from "fast-xml-parser";
import { parse as parseYaml } from "yaml";
import { expectArray, expectColor, expectString } from "./validation.mjs";

function normalizeTechStacks(rawData) {
    const categories = expectArray(rawData?.categories, "tech-stacks.categories").map(
        (category, categoryIndex) => ({
            name: expectString(category?.name, `categories[${categoryIndex}].name`),
            items: expectArray(
                category?.items,
                `categories[${categoryIndex}].items`,
            ).map((item, itemIndex) => ({
                name: expectString(
                    item?.name,
                    `categories[${categoryIndex}].items[${itemIndex}].name`,
                ),
                color: expectColor(
                    item?.color,
                    `categories[${categoryIndex}].items[${itemIndex}].color`,
                ),
            })),
        }),
    );
    const technologyNames = new Set(
        categories.flatMap((category) => category.items.map((item) => item.name)),
    );
    const rawPositions = rawData?.positions;

    if (
        !rawPositions ||
        typeof rawPositions !== "object" ||
        Array.isArray(rawPositions)
    ) {
        throw new Error("tech-stacks.positions must be an object.");
    }

    const positions = Object.entries(rawPositions).map(
        ([name, position], positionIndex) => {
            const positionName = expectString(name, `positions[${positionIndex}].name`);
            const technologies = expectArray(
                position?.technologies,
                `positions.${positionName}.technologies`,
            );

            technologies.forEach((technology) => {
                if (!technologyNames.has(technology)) {
                    throw new Error(
                        `positions.${positionName} references unknown technology: ${technology}`,
                    );
                }
            });

            return {
                color: expectColor(position?.color, `positions.${positionName}.color`),
                description: expectString(
                    position?.description,
                    `positions.${positionName}.description`,
                ),
                name: positionName,
                technologies,
            };
        },
    );

    return { categories, positions };
}

function normalizeTeam(rawData) {
    const rawMembers = expectArray(rawData?.team?.member, "team.member");
    const githubHandles = new Set();

    return rawMembers.map((member, memberIndex) => {
        const github = expectString(member?.github, `member[${memberIndex}].github`);
        if (githubHandles.has(github.toLowerCase())) {
            throw new Error(`Duplicate GitHub handle in team-members.xml: ${github}`);
        }
        githubHandles.add(github.toLowerCase());

        const roles = (Array.isArray(member.role) ? member.role : [member.role]).map(
            (role, roleIndex) =>
                expectString(role, `member[${memberIndex}].role[${roleIndex}]`),
        );

        return {
            accent: expectColor(member?.accent, `member[${memberIndex}].accent`),
            generation: expectString(
                member?.generation,
                `member[${memberIndex}].generation`,
            ),
            github,
            name: expectString(member?.name, `member[${memberIndex}].name`),
            roles,
        };
    });
}

function validateTeamRoles(team, positions) {
    const positionNames = new Set(positions.map((position) => position.name));

    team.forEach((member) => {
        member.roles.forEach((role) => {
            if (!positionNames.has(role)) {
                throw new Error(`${member.github} references unknown position: ${role}`);
            }
        });
    });
}

export function parseContent({ teamXml, techStackYaml }) {
    const techStacks = normalizeTechStacks(parseYaml(techStackYaml));
    const team = normalizeTeam(
        new XMLParser({
            attributeNamePrefix: "",
            ignoreAttributes: false,
            parseAttributeValue: false,
            trimValues: true,
        }).parse(teamXml),
    );

    validateTeamRoles(team, techStacks.positions);
    return { team, techStacks };
}
