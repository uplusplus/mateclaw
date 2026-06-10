package vip.mate.skill.installer.model;

/**
 * Async marketplace stats payload used by the search dialog.
 *
 * @author MateClaw Team
 */
public record HubSkillStats(
        String slug,
        Integer downloads,
        Integer stars,
        String version
) {}
