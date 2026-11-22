package dev.keneq.dsm.state;

public enum MusicState {
    BIOME("biome", false, false),
    MENU_PAUSE("menu_pause", true, false),
    MENU_INVENTORY("menu_inventory", true, false),

    DANGER("danger", false, true),
    GROUP_DANGER("group_danger", false, true),
    ESCAPE("escape", false, true),
    SEARCH("search", false, false),
    HIDE("hide", false, false),

    PVP_DANGER("pvp_danger", false, true),
    PVP_GROUP_DANGER("pvp_group_danger", false, true),
    PVP_ESCAPE("pvp_escape", false, true),
    PVP_SEARCH("pvp_search", false, false),
    PVP_HIDE("pvp_hide", false, false),

    LOW_HEALTH("low_health", false, false),
    NEAR_DEATH("near_death", false, true),
    HEALING("healing", false, false),
    DEATH("death", false, true),

    BOSS("boss", false, true),
    JUMPSCARE("jumpscare", false, true);

    private final String id;
    private final boolean menu;
    private final boolean instantAttack;

    MusicState(String id, boolean menu, boolean instantAttack) {
        this.id = id;
        this.menu = menu;
        this.instantAttack = instantAttack;
    }

    public String getId() {
        return id;
    }

    public boolean isMenu() {
        return menu;
    }

    public boolean isInstantAttack() {
        return instantAttack;
    }
}