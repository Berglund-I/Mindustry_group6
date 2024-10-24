import arc.*;
import arc.backend.headless.*;
import arc.files.*;
import arc.math.geom.*;
import arc.scene.Scene;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import arc.util.serialization.JsonValue.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.io.SaveIO.*;
import mindustry.maps.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.storage.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.*;

public class ApplicationTests{
    static Map testMap;
    static boolean initialized;
    //core/assets
    static final Fi testDataFolder = new Fi("../../tests/build/test_data");

    @BeforeAll
    public static void launchApplication(){
        launchApplication(true);
    }

    public static void launchApplication(boolean clear){
        //only gets called once
        if(initialized) return;
        initialized = true;

        try{
            boolean[] begins = {false};
            Throwable[] exceptionThrown = {null};
            Log.useColors = false;

            ApplicationCore core = new ApplicationCore(){
                @Override
                public void setup(){
                    //clear older data
                    if(clear){
                        ApplicationTests.testDataFolder.deleteDirectory();
                    }

                    Core.settings.setDataDirectory(testDataFolder);
                    headless = true;
                    net = new Net(null);
                    tree = new FileTree();
                    Vars.init();
                    world = new World(){
                        @Override
                        public float getDarkness(int x, int y){
                            //for world borders
                            return 0;
                        }
                    };
                    content.createBaseContent();
                    mods.loadScripts();
                    content.createModContent();

                    add(logic = new Logic());
                    add(netServer = new NetServer());

                    content.init();

                    mods.eachClass(Mod::init);

                    if(mods.hasContentErrors()){
                        for(LoadedMod mod : mods.list()){
                            if(mod.hasContentErrors()){
                                for(Content cont : mod.erroredContent){
                                    throw new RuntimeException("error in file: " + cont.minfo.sourceFile.path(), cont.minfo.baseError);
                                }
                            }
                        }
                    }

                }

                @Override
                public void init(){
                    super.init();
                    begins[0] = true;
                    testMap = maps.loadInternalMap("groundZero");
                    Thread.currentThread().interrupt();
                }
            };

            new HeadlessApplication(core, throwable -> exceptionThrown[0] = throwable);

            while(!begins[0]){
                if(exceptionThrown[0] != null){
                    fail(exceptionThrown[0]);
                }
                Thread.sleep(10);
            }

            Block block = content.getByName(ContentType.block, "build2");
            assertEquals("build2", block == null ? null : block.name, "2x2 construct block doesn't exist?");
        }catch(Throwable r){
            fail(r);
        }
    }

    @BeforeEach
    void resetWorld(){
        Time.setDeltaProvider(() -> 1f);
        logic.reset();
        state.set(State.menu);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
    "a",
    "asd asd asd asd asdagagasasjakbgeah;jwrej 23424234",
    "这个服务器可以用自己的语言说话",
    "\uD83D\uDEA3"
    })
    void writeStringTest(String string){
        ByteBuffer buffer = ByteBuffer.allocate(500);
        TypeIO.writeString(buffer, string);
        buffer.position(0);
        assertEquals(TypeIO.readString(buffer), string);

        ByteArrayOutputStream ba = new ByteArrayOutputStream();

        TypeIO.writeString(new Writes(new DataOutputStream(ba)), string);
        assertEquals(TypeIO.readString(new Reads(new DataInputStream(new ByteArrayInputStream(ba.toByteArray())))), string);

        SendChatMessageCallPacket pack = new SendChatMessageCallPacket();
        pack.message = string;

        buffer.position(0);
        pack.write(new Writes(new ByteBufferOutput(buffer)));
        int len = buffer.position();
        buffer.position(0);
        pack.message = "INVALID";
        pack.read(new Reads(new ByteBufferInput(buffer)), len);
        pack.handled();

        assertEquals(string, pack.message);

        buffer.position(0);
        Writes writes = new Writes(new ByteBufferOutput(buffer));
        TypeIO.writeString(writes, string);

        buffer.position(0);

        assertEquals(string, TypeIO.readString(new Reads(new ByteBufferInput(buffer))));

        buffer.position(0);
        ConnectPacket con = new ConnectPacket();
        con.name = string;
        con.uuid = "AAAAAAAA";
        con.usid = "AAAAAAAA";
        con.mods = new Seq<>();
        con.write(new Writes(new ByteBufferOutput(buffer)));

        con.name = "INVALID";
        buffer.position(0);
        con.read(new Reads(new ByteBufferInput(buffer)));

        assertEquals(string, con.name);
    }

    @Test
    void writeRules(){
        ByteBuffer buffer = ByteBuffer.allocate(1000);

        Rules rules = new Rules();
        rules.attackMode = true;
        rules.buildSpeedMultiplier = 99f;

        TypeIO.writeRules(new Writes(new ByteBufferOutput(buffer)), rules);
        buffer.position(0);
        Rules res = TypeIO.readRules(new Reads(new ByteBufferInput(buffer)));

        assertEquals(rules.buildSpeedMultiplier, res.buildSpeedMultiplier);
        assertEquals(rules.attackMode, res.attackMode);
    }

    @Test
    void writeRules2(){
        Rules rules = new Rules();
        rules.attackMode = true;
        rules.tags.put("blah", "bleh");
        rules.buildSpeedMultiplier = 99.1f;

        String str = JsonIO.write(rules);
        Rules res = JsonIO.read(Rules.class, str);

        assertEquals(rules.buildSpeedMultiplier, res.buildSpeedMultiplier);
        assertEquals(rules.attackMode, res.attackMode);
        assertEquals(rules.tags.get("blah"), res.tags.get("blah"));

        String str2 = JsonIO.write(new Rules(){{
            attackMode = true;
        }});
    }

    //Test case for copy() in Rules class
    @Test
    void testCopy(){
        //Arrange Rules object and add some values
        Rules originalRules = new Rules();
        originalRules.blockDamageMultiplier = 2.0f;
        originalRules.unitHealthMultiplier = 1.5f;
        originalRules.infiniteResources = true;

        //Act use the copy() method to copy the object
        Rules copiedRules = originalRules.copy();

        //Assert and make sure all values from originalRules are the same as from copiedRules
        assertEquals(originalRules.blockDamageMultiplier, copiedRules.blockDamageMultiplier, "Block damage should be the same");
        assertEquals(originalRules.unitHealthMultiplier, copiedRules.unitHealthMultiplier, "Unit health should be the same");
        assertEquals(originalRules.infiniteResources, copiedRules.infiniteResources, "Infinate rescourses should be the same");

        //Assert and make sure the copy is a new object
        assertNotSame(originalRules, copiedRules);
        assertNotSame(originalRules.teams, copiedRules.teams);

    }

    //Test case for unitCrashDamage()
    @Test
    void testUnitCrashDamage(){
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;

        //Enter values
        rules.unitDamageMultiplier = 2.0f;
        rules.unitCrashDamageMultiplier = 1.5f;
        rules.teams.get(team).unitDamageMultiplier = 1.2f;
        rules.teams.get(team).unitCrashDamageMultiplier = 1.1f;

        //Act
        float actualCrashDamage = rules.unitCrashDamage(team);

        //Expected values
        float unitDamage = rules.unitDamageMultiplier * rules.teams.get(team).unitDamageMultiplier; // 2.0 * 1.2 = 2.4
        float expectedCrashDamage = unitDamage * rules.unitCrashDamageMultiplier * rules.teams.get(team).unitCrashDamageMultiplier; //2.4 * 1.5 * 1.1 = 3.96

        assertEquals(expectedCrashDamage, actualCrashDamage, 0.001f, "Crash damage should match expectedCrashDamage");
    }

    //Test case for blockDamage()
    @Test
    void testBlockDamage(){
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;

        rules.blockDamageMultiplier = 2.0f;

        //Mock
        rules.teams.get(team).blockDamageMultiplier = 1.5f;

        //Act
        float actualBlockDamage = rules.blockDamage(team);

        //Assert
        float expectedBlockDamage = 2.0f * 1.5f;
        assertEquals(expectedBlockDamage, actualBlockDamage, 0.001f); //0.001f för eventuella flyttals avvikelser

    }

    //Test for blockHealth()
    @Test
    void testBlockHealth(){
        Rules rules = new Rules();
        Team team = Team.sharded;

        rules.blockHealthMultiplier = 2.0f;

        //Mock
        rules.teams.get(team).blockHealthMultiplier = 1.5f;

        //Act
        float actualHealthDamage = rules.blockHealth(team);

        //Assert
        float excpectedHealthDamage = 2.0f * 1.5f;
        assertEquals(excpectedHealthDamage, actualHealthDamage, 0.001f);
    }

    @Test
    void testMode_shouldReturnPvp_whenPvpIsTrue(){
        //Ida Gamemode mode()
        //Arrange
        Rules rules = new Rules();
        rules.pvp = true;

        //Act
        Gamemode result = rules.mode();

        //Assert
        assertEquals(Gamemode.pvp, result);
    }

    @Test
    void testMode_shouldReturnEditor_whenEditorIsTrue(){
        //Ida Gamemode mode()
        //Arrange
        Rules rules = new Rules();
        rules.editor = true;

        //Act
        Gamemode result = rules.mode();

        //Assert
        assertEquals(Gamemode.editor, result);
    }

    @Test
    void testMode_shouldReturnAttack_whenAttackModelsIsTrue(){
        //Ida Gamemode mode()
        // Arrange
        Rules rules = new Rules();
        rules.attackMode = true;

        //Act
        Gamemode result = rules.mode();

        //Assert
        assertEquals(Gamemode.attack, result);
    }

    @Test
    void testMode_shouldReturnSandbox_whenInifiteResourcesIsTrue(){
        //Ida Gamemode mode()
        //Arrange
        Rules rules = new Rules();
        rules.infiniteResources = true;

        //Act
        Gamemode result = rules.mode();

        //Assert
        assertEquals(Gamemode.sandbox, result);
    }

    @Test
    void testMode_shouldReturnSurvival_whenNoOtherModesAreTrue() {
        //Ida Gamemode mode()
        //Arrange
        Rules rules = new Rules();

        //Act
        Gamemode result = rules.mode();

        //Assert
        assertEquals(Gamemode.survival, result);
    }

    @Test
    void testUnitDamage_shouldReturnCorrectValue_whenTeamAndRulesHaveDefaultMultipliers() {
        //Ida, UnitDamage(Team team)
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitDamageMultiplier = 1.0f;
        rules.teams.get(team).unitDamageMultiplier = 1.0f;

        //Act
        float result = rules.unitDamage(team);

        //Assert
        assertEquals(1.0f, result);
    }

    @Test
    void testUnitDamage_shouldReturnCorrectValue_whenTeamHasCustomMultiplier() {
        //Ida, UnitDamage(Team team)
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitDamageMultiplier = 2.0f;
        rules.teams.get(team).unitDamageMultiplier = 1.5f;

        //Act
        float result = rules.unitDamage(team);

        //Assert
        assertEquals(3.0f, result);
    }

    @Test
    void testUnitDamage_shouldReturnZero_whenOneMultiplierIsZero() {
        //Ida, UnitDamage(Team team)
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitDamageMultiplier = 0.0f;
        rules.teams.get(team).unitDamageMultiplier = 1.5f;

        //Act
        float result = rules.unitDamage(team);

        //Assert
        assertEquals(0.0f, result);
    }

    @Test
    void testUnitDamage_shouldReturnCorrectValue_whenBothMultipliersAreNonDefault() {
        //Ida, UnitDamage(Team team)
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitDamageMultiplier = 1.2f;
        rules.teams.get(team).unitDamageMultiplier = 2.5f;

        //Act
        float result = rules.unitDamage(team);

        //Assert
        assertEquals(3.0f, result);
    }

    @Test
    void testUnitHealth_shouldReturnMinimumValue_whenMultiplicationResultIsZero(){
        //Ida unitHealth
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitHealthMultiplier = 0.0f;
        rules.teams.get(team).unitHealthMultiplier = 1.5f;

        //Act
        float result = rules.unitHealth(team);

        //Assert
        assertEquals(0.000001f, result);
    }

    @Test
    void testUnitHealth_shouldReturnCorrectValue_whenMultiplicationResultIsPositive(){
        //Ida unitHealth
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitHealthMultiplier = 1.2f;
        rules.teams.get(team).unitHealthMultiplier = 2.0f;

        //Act
        float result = rules.unitHealth(team);

        //Assert
        assertEquals(2.4f, result);
    }

    @Test
    void testUnitHealth_shouldReturnMinimumValue_whenMultiplicationResultIsNegative(){
        //Ida unitHealth
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitHealthMultiplier = -1.0f;
        rules.teams.get(team).unitHealthMultiplier = 2.0f;

        //Act
        float result = rules.unitHealth(team);

        //Assert
        assertEquals(0.000001f, result);
    }

    @Test
    void testUnitHealth_shouldReturnCorrectValue_whenBothMultipliersAreOne(){
        //Ida unitHealth
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitHealthMultiplier = 1.0f;
        rules.teams.get(team).unitHealthMultiplier = 1.0f;

        //Act
        float result = rules.unitHealth(team);

        //Assert
        assertEquals(1.0f, result);
    }

    @Test
    void testUnitHealth_shouldReturnCorrectValue_whenOneMultiplierIsFractional(){
        //Ida unitHealth
        //Arrange
        Rules rules = new Rules();
        Team team = Team.sharded;
        rules.unitHealthMultiplier = 0.5f;
        rules.teams.get(team).unitHealthMultiplier = 0.8f;

        //Act
        float result = rules.unitHealth(team);

        //Assert
        assertEquals(0.4f, result);
    }

    @Test
    void serverListJson(){
        String[] files = {"servers_v6.json", "servers_v7.json", "servers_be.json"};


        for(String file : files){
            try{
                String str = Core.files.absolute("./../../" + file).readString();
                assertEquals(ValueType.array, new JsonReader().parse(str).type());
                assertTrue(Jval.read(str).isArray());
                JSONArray array = new JSONArray(str);
                assertTrue(array.length() > 0);
            }catch(Exception e){
                fail("Failed to parse " + file, e);
            }
        }
    }

    @Test
    void initialization(){
        assertNotNull(logic);
        assertNotNull(world);
        assertTrue(content.getContentMap().length > 0);
    }

    @Test
    void playMap(){
        world.loadMap(testMap);
    }

    @Test
    void spawnWaves(){
        world.loadMap(testMap);
        assertTrue(spawner.countSpawns() > 0, "No spawns present.");
        logic.runWave();
        //force trigger delayed spawns
        Time.setDeltaProvider(() -> 1000f);
        Time.update();
        Time.update();
        Groups.unit.update();
        assertFalse(Groups.unit.isEmpty(), "No enemies spawned.");
    }

    @Test
    void createMap(){
        Tiles tiles = world.resize(8, 8);

        world.beginMapLoad();
        tiles.fill();
        world.endMapLoad();
    }

    @Test
    void multiblock(){
        createMap();
        int bx = 4;
        int by = 4;
        world.tile(bx, by).setBlock(Blocks.coreShard, Team.sharded, 0);
        assertEquals(world.tile(bx, by).team(), Team.sharded);
        for(int x = bx - 1; x <= bx + 1; x++){
            for(int y = by - 1; y <= by + 1; y++){
                assertEquals(world.tile(x, y).block(), Blocks.coreShard);
                assertEquals(world.tile(x, y).build, world.tile(bx, by).build);
            }
        }
    }

    @Test
    void blockInventories(){
        multiblock();
        Tile tile = world.tile(4, 4);
        tile.build.items.add(Items.coal, 5);
        tile.build.items.add(Items.titanium, 50);
        assertEquals(tile.build.items.total(), 55);
        tile.build.items.remove(Items.phaseFabric, 10);
        tile.build.items.remove(Items.titanium, 10);
        assertEquals(tile.build.items.total(), 45);
    }

    @Test
    void timers(){
        boolean[] ran = {false};
        Time.run(1.9999f, () -> ran[0] = true);

        Time.update();
        assertFalse(ran[0]);
        Time.update();
        assertTrue(ran[0]);
    }

    @Test
    void manyTimers(){
        int runs = 100000;
        int[] total = {0};
        for(int i = 0; i < runs; i++){
            Time.run(0.999f, () -> total[0]++);
        }
        assertEquals(0, total[0]);
        Time.update();
        assertEquals(runs, total[0]);
    }

    @Test
    void longTimers(){
        Time.setDeltaProvider(() -> Float.MAX_VALUE);
        Time.update();
        int steps = 100;
        float delay = 100000f;
        Time.setDeltaProvider(() -> delay / steps + 0.01f);
        int runs = 100000;
        int[] total = {0};
        for(int i = 0; i < runs; i++){
            Time.run(delay, () -> total[0]++);
        }
        assertEquals(0, total[0]);
        for(int i = 0; i < steps; i++){
            Time.update();
        }
        assertEquals(runs, total[0]);
    }

    @Test
    void save(){
        world.loadMap(testMap);
        assertTrue(state.teams.playerCores().size > 0);
        SaveIO.save(saveDirectory.child("0.msav"));
    }

    @Test
    void saveLoad(){
        world.loadMap(testMap);
        Map map = state.map;

        float hp = 30f;

        Unit unit = UnitTypes.dagger.spawn(Team.sharded, 20f, 30f);
        unit.health = hp;

        SaveIO.save(saveDirectory.child("0.msav"));
        resetWorld();
        SaveIO.load(saveDirectory.child("0.msav"));

        Unit spawned = Groups.unit.find(u -> u.type == UnitTypes.dagger);
        assertNotNull(spawned, "Saved daggers must persist");
        assertEquals(hp, spawned.health, "Spawned dagger health must save.");

        assertEquals(world.width(), map.width);
        assertEquals(world.height(), map.height);
        assertTrue(state.teams.playerCores().size > 0);
    }

    @Test
    void testSaveNotNull() {
        // Andreas
        // Arrange
        if (arc.Core.assets == null) {
            Core.assets = new arc.assets.AssetManager();
        }
        // Act
        Saves save = new Saves();
        // Assert
        assertFalse(save.isSaving());
    }

    @Test
    void testSaveStateChangeToMenu() {
        // Andreas
        // Arrange
        if (arc.Core.assets == null) {
            Core.assets = new arc.assets.AssetManager();
        }
        // Act
        Saves save = new Saves();
        Events.fire(new EventType.StateChangeEvent(State.playing, State.menu));
        // Assert
        assertNull(save.getCurrent());
    }

    @Test
    void testSaveUpdateTimeStampEqualToZero() {
        // Andreas
        // Arrange
        if (arc.Core.assets == null) {
            Core.assets = new arc.assets.AssetManager();
        }
        // Act
        Saves save = new Saves();
        try {
            Field field = Saves.class.getDeclaredField("lastTimestamp");
            field.setAccessible(true);
            field.set(save, 1000L);
        }
        catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        // Assert
        assertTrue(save.getTotalPlaytime() == 0);
    }

    @Test
    void testSaveUpdateWhenStateIsNotPausedAndHasDialog() {
        // Andreas
        // Arrange
        if (arc.Core.assets == null) {
            Core.assets = new arc.assets.AssetManager();
        }
        // Act
        Saves save = new Saves();
        try {
            Field field = Saves.class.getDeclaredField("lastTimestamp");
            field.setAccessible(true);
            field.set(save, System.currentTimeMillis());
        }
        catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        GameState state = mock(GameState.class);
        Scene scene = mock(Scene.class);
        when(state.isGame()).thenReturn(false);
        when(state.isPaused()).thenReturn(true);
        when(scene.hasDialog()).thenReturn(false);
        Core.scene = scene;
        Vars.state = state;

        save.update();

        // Assert
        assertTrue(save.getTotalPlaytime() >= 0);
    }

    @Test
    void testSaveSaveSlotConstructor() {
        // Andreas
        // Arrange
        if (arc.Core.assets == null) {
            Core.assets = new arc.assets.AssetManager();
        }

        // Act
        Fi mockFile = mock(Fi.class);
        Saves.SaveSlot saveSlot = new Saves().new SaveSlot(mockFile);
        // Assert
        assertEquals(mockFile, saveSlot.file);
    }

    void updateBlocks(int times){
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.isCenter()){
                tile.build.updateProximity();
            }
        }

        for(int i = 0; i < times; i++){
            Time.update();
            for(Tile tile : world.tiles){
                if(tile.build != null && tile.isCenter()){
                    tile.build.update();
                }
            }
        }
    }

    @Test
    void liquidOutput(){
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;

        world.tile(0, 0).setBlock(Blocks.liquidSource, Team.sharded);
        world.tile(0, 0).build.configureAny(Liquids.water);

        world.tile(2, 1).setBlock(Blocks.liquidTank, Team.sharded);

        updateBlocks(10);

        assertTrue(world.tile(2, 1).build.liquids.currentAmount() >= 1);
        assertTrue(world.tile(2, 1).build.liquids.current() == Liquids.water);
    }

    @Test
    void liquidJunctionOutput(){
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;

        Tile source = world.rawTile(0, 0), tank = world.rawTile(1, 4), junction = world.rawTile(0, 1), conduit = world.rawTile(0, 2);

        source.setBlock(Blocks.liquidSource, Team.sharded);
        source.build.configureAny(Liquids.water);

        junction.setBlock(Blocks.liquidJunction, Team.sharded);

        conduit.setBlock(Blocks.conduit, Team.sharded, 1);

        tank.setBlock(Blocks.liquidTank, Team.sharded);

        updateBlocks(10);

        assertTrue(tank.build.liquids.currentAmount() >= 1, "Liquid not moved through junction");
        assertTrue(tank.build.liquids.current() == Liquids.water, "Tank has no water");
    }

    @Test
    void liquidRouterOutputAll() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source = world.rawTile(4,0), router = world.rawTile(4, 2), conduitUp1 = world.rawTile(4,1),
        conduitLeft = world.rawTile(3,2), conduitUp2 = world.rawTile(4, 3), conduitRight = world.rawTile(5, 2),
        leftTank = world.rawTile(1, 2), topTank = world.rawTile(4,5), rightTank = world.rawTile(7, 2);

        source.setBlock(Blocks.liquidSource, Team.sharded);
        source.build.configureAny(Liquids.water);
        conduitUp1.setBlock(Blocks.conduit, Team.sharded, 1);
        router.setBlock(Blocks.liquidRouter, Team.sharded);
        conduitLeft.setBlock(Blocks.conduit, Team.sharded,2);
        conduitUp2.setBlock(Blocks.conduit, Team.sharded, 1);
        conduitRight.setBlock(Blocks.conduit, Team.sharded, 0);
        leftTank.setBlock(Blocks.liquidTank, Team.sharded);
        topTank.setBlock(Blocks.liquidTank, Team.sharded);
        rightTank.setBlock(Blocks.liquidTank, Team.sharded);

        updateBlocks(200);
        assertTrue(rightTank.build.liquids.currentAmount() > 0, "Liquid router did not distribute to rightTank");
        assertTrue(topTank.build.liquids.currentAmount() > 0, "Liquid router did not distribute to topTank");
        assertTrue(leftTank.build.liquids.currentAmount() > 0, "Liquid router did not distribute to rightTank");
    }

    @Test
    void sorterOutputCorrect() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source1 = world.rawTile(4, 0), source2 = world.rawTile(6, 0), s1conveyor = world.rawTile(4, 1),
        s2conveyor = world.rawTile(6, 1), s1s2conveyor = world.rawTile(5, 1), sorter = world.rawTile(5, 2),
        leftconveyor = world.rawTile(4, 2), rightconveyor = world.rawTile(6, 2), sortedconveyor = world.rawTile(5, 3),
        leftVault = world.rawTile(2, 2), rightVault = world.rawTile(8, 2), topVault = world.rawTile(5, 5);

        source1.setBlock(Blocks.itemSource, Team.sharded);
        source1.build.configureAny(Items.coal);
        source2.setBlock(Blocks.itemSource, Team.sharded);
        source2.build.configureAny(Items.copper);
        s1conveyor.setBlock(Blocks.conveyor, Team.sharded, 0);
        s2conveyor.setBlock(Blocks.conveyor, Team.sharded, 2);
        s1s2conveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        sorter.setBlock(Blocks.sorter, Team.sharded);
        sorter.build.configureAny(Items.copper);
        leftconveyor.setBlock(Blocks.conveyor, Team.sharded, 2);
        rightconveyor.setBlock(Blocks.conveyor, Team.sharded, 0);
        sortedconveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        leftVault.setBlock(Blocks.vault, Team.sharded);
        rightVault.setBlock(Blocks.vault, Team.sharded);
        topVault.setBlock(Blocks.vault, Team.sharded);

        updateBlocks(200);
        assertEquals(Items.coal, rightVault.build.items.first());
        assertEquals(Items.copper, topVault.build.items.first());
        assertEquals(Items.coal, leftVault.build.items.first());

    }

    @Test
    void routerOutputAll() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source1 = world.rawTile(5, 0),  conveyor = world.rawTile(5, 1),
        router = world.rawTile(5, 2), leftconveyor = world.rawTile(4, 2), rightconveyor = world.rawTile(6, 2),
        middleconveyor = world.rawTile(5, 3), leftVault = world.rawTile(2, 2),
        rightVault = world.rawTile(8, 2), topVault = world.rawTile(5, 5);

        source1.setBlock(Blocks.itemSource, Team.sharded);
        source1.build.configureAny(Items.coal);
        conveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        router.setBlock(Blocks.router, Team.sharded);
        router.build.configureAny(Items.coal);
        leftconveyor.setBlock(Blocks.conveyor, Team.sharded, 2);
        rightconveyor.setBlock(Blocks.conveyor, Team.sharded, 0);
        middleconveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        leftVault.setBlock(Blocks.vault, Team.sharded);
        rightVault.setBlock(Blocks.vault, Team.sharded);
        topVault.setBlock(Blocks.vault, Team.sharded);

        updateBlocks(200);
        assertEquals(Items.coal, rightVault.build.items.first());
        assertEquals(Items.coal, topVault.build.items.first());
        assertEquals(Items.coal, leftVault.build.items.first());
    }

    @Test
    void junctionOutputCorrect() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source1 = world.rawTile(5,0),source2 = world.rawTile(7, 2),  conveyor1 = world.rawTile(5, 1),
        conveyor2 = world.rawTile(6,2), junction = world.rawTile(5, 2), conveyor3 = world.rawTile(5,3),
        conveyor4 = world.rawTile(4,2), vault2 = world.rawTile(3, 1), vault1 = world.rawTile(5,5);
        source1.setBlock(Blocks.itemSource, Team.sharded);
        source1.build.configureAny(Items.coal);
        source2.setBlock(Blocks.itemSource, Team.sharded);
        source2.build.configureAny(Items.copper);
        conveyor1.setBlock(Blocks.conveyor, Team.sharded, 1);
        conveyor2.setBlock(Blocks.conveyor, Team.sharded, 2);
        conveyor3.setBlock(Blocks.conveyor, Team.sharded, 1);
        conveyor4.setBlock(Blocks.conveyor, Team.sharded, 2);
        junction.setBlock(Blocks.junction, Team.sharded);

        vault1.setBlock(Blocks.vault, Team.sharded);
        vault2.setBlock(Blocks.vault, Team.sharded);

        updateBlocks(200);
        assertEquals(Items.coal, vault1.build.items.first());
        assertEquals(Items.copper, vault2.build.items.first());
    }

    @Test
    void blockOverlapRemoved(){
        world.loadMap(testMap);
        state.set(State.playing);

        //edge block
        world.tile(1, 1).setBlock(Blocks.coreShard);
        assertEquals(Blocks.coreShard, world.tile(0, 0).block());

        //this should overwrite the block
        world.tile(2, 2).setBlock(Blocks.coreShard);
        assertEquals(Blocks.air, world.tile(0, 0).block());
    }

    @Test
    void conveyorCrash(){
        world.loadMap(testMap);
        state.set(State.playing);

        world.tile(0, 0).setBlock(Blocks.conveyor);
        world.tile(0, 0).build.acceptStack(Items.copper, 1000, null);
    }

    @Test
    void conveyorBench(){
        int[] itemsa = {0};

        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        int length = 128;
        world.tile(0, 0).setBlock(Blocks.itemSource, Team.sharded);
        world.tile(0, 0).build.configureAny(Items.copper);

        Seq<Building> entities = Seq.with(world.tile(0, 0).build);

        for(int i = 0; i < length; i++){
            world.tile(i + 1, 0).setBlock(Blocks.conveyor, Team.sharded, 0);
            entities.add(world.tile(i + 1, 0).build);
        }

        world.tile(length + 1, 0).setBlock(new Block("___"){{
            hasItems = true;
            destructible = true;
            buildType = () -> new Building(){
                @Override
                public void handleItem(Building source, Item item){
                    itemsa[0] ++;
                }

                @Override
                public boolean acceptItem(Building source, Item item){
                    return true;
                }
            };
        }}, Team.sharded);

        entities.each(Building::updateProximity);

        //warmup
        for(int i = 0; i < 100000; i++){
            entities.each(Building::update);
        }

        Time.mark();
        for(int i = 0; i < 200000; i++){
            entities.each(Building::update);
        }
        Log.info(Time.elapsed() + "ms to process " + itemsa[0] + " items");
        assertNotEquals(0, itemsa[0]);
    }

    @Test
    void load77Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("77.msav"));

        //just tests if the map was loaded properly and didn't crash, no validity checks currently
        assertEquals(276, world.width());
        assertEquals(10, world.height());
    }

    @Test
    void load85Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("85.msav"));

        assertEquals(250, world.width());
        assertEquals(300, world.height());
    }

    @Test
    void load108Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("108.msav"));

        assertEquals(256, world.width());
        assertEquals(256, world.height());
    }

    @Test
    void load114Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("114.msav"));

        assertEquals(500, world.width());
        assertEquals(500, world.height());
    }

    @Test
    void arrayIterators(){
        Seq<String> arr = Seq.with("a", "b" , "c", "d", "e", "f");
        Seq<String> results = new Seq<>();

        for(String s : arr);
        for(String s : results);

        Seq.iteratorsAllocated = 0;

        //simulate non-enhanced for loops, which should be correct

        for(int i = 0; i < arr.size; i++){
            for(int j = 0; j < arr.size; j++){
                results.add(arr.get(i) + arr.get(j));
            }
        }

        int index = 0;

        //test nested for loops
        for(String s : arr){
            for(String s2 : arr){
                assertEquals(results.get(index++), s + s2);
            }
        }

        assertEquals(results.size, index);
        assertEquals(0, Seq.iteratorsAllocated, "No new iterators must have been allocated.");
    }

    @Test
    void inventoryDeposit(){
        depositTest(Blocks.surgeSmelter, Items.copper);
        depositTest(Blocks.vault, Items.copper);
        depositTest(Blocks.thoriumReactor, Items.thorium);
    }

    @Test
    void edges(){
        Point2[] edges = Edges.getEdges(1);
        assertEquals(edges[0], new Point2(1, 0));
        assertEquals(edges[1], new Point2(0, 1));
        assertEquals(edges[2], new Point2(-1, 0));
        assertEquals(edges[3], new Point2(0, -1));

        Point2[] edges2 = Edges.getEdges(2);
        assertEquals(8, edges2.length);
    }

    @Test
    void buildingOverlap(){
        initBuilding();

        Unit d1 = UnitTypes.poly.create(Team.sharded);
        Unit d2 = UnitTypes.poly.create(Team.sharded);

        //infinite build range
        state.rules.editor = true;
        state.rules.infiniteResources = true;
        state.rules.buildSpeedMultiplier = 999999f;

        d1.set(0f, 0f);
        d2.set(20f, 20f);

        d1.addBuild(new BuildPlan(0, 0, 0, Blocks.copperWallLarge));
        d2.addBuild(new BuildPlan(1, 1, 0, Blocks.copperWallLarge));

        d1.update();
        d2.update();

        assertEquals(Blocks.copperWallLarge, world.tile(0, 0).block());
        assertEquals(Blocks.air, world.tile(2, 2).block());
        assertEquals(Blocks.copperWallLarge, world.tile(1, 1).block());
        assertEquals(world.tile(1, 1).build, world.tile(0, 0).build);
    }

    @Test
    void buildingDestruction(){
        initBuilding();

        Builderc d1 = UnitTypes.poly.create(Team.sharded);
        Builderc d2 = UnitTypes.poly.create(Team.sharded);

        d1.set(10f, 20f);
        d2.set(10f, 20f);

        d1.addBuild(new BuildPlan(0, 0, 0, Blocks.copperWallLarge));
        d2.addBuild(new BuildPlan(1, 1));

        Time.setDeltaProvider(() -> 3f);
        d1.update();
        Time.setDeltaProvider(() -> 1f);
        d2.update();

        assertEquals(content.getByName(ContentType.block, "build2"), world.tile(0, 0).block());

        Time.setDeltaProvider(() -> 9999f);

        //prevents range issues
        state.rules.infiniteResources = true;

        d1.update();

        assertEquals(Blocks.copperWallLarge, world.tile(0, 0).block());
        assertEquals(Blocks.copperWallLarge, world.tile(1, 1).block());

        d2.clearBuilding();
        d2.addBuild(new BuildPlan(1, 1));

        for(int i = 0; i < 3; i++){
            d2.update();
        }

        assertEquals(Blocks.air, world.tile(0, 0).block());
        assertEquals(Blocks.air, world.tile(2, 2).block());
        assertEquals(Blocks.air, world.tile(1, 1).block());
    }

    @Test
    void allBlockTest(){
        Tiles tiles = world.resize(80, 80);

        world.beginMapLoad();
        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air));
            }
        }
        int maxHeight = 0;
        state.rules.canGameOver = false;
        state.rules.borderDarkness = false;

        for(int x = 0, y = 0, i = 0; i < content.blocks().size; i ++){
            Block block = content.block(i);
            if(block.canBeBuilt()){
                int offset = Math.max(block.size % 2 == 0 ? block.size/2 - 1 : block.size/2, 0);

                if(x + block.size + 1 >= world.width()){
                    y += maxHeight;
                    maxHeight = 0;
                    x = 0;
                }

                tiles.get(x + offset, y + offset).setBlock(block);
                x += block.size;
                maxHeight = Math.max(maxHeight, block.size);
            }
        }
        world.endMapLoad();

        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                Tile tile = world.rawTile(x, y);
                if(tile.build != null){
                    try{
                        tile.build.update();
                    }catch(Throwable t){
                        fail("Failed to update block '" + tile.block() + "'.", t);
                    }
                    assertEquals(tile.block(), tile.build.block);
                    assertEquals(tile.block().health, tile.build.health());
                }
            }
        }
    }

    void checkPayloads(){
        for(int x = 0; x < world.tiles.width; x++){
            for(int y = 0; y < world.tiles.height; y++){
                Tile tile = world.rawTile(x, y);
                if(tile.build != null && tile.isCenter() && !(tile.block() instanceof CoreBlock)){
                    try{
                        tile.build.update();
                    }catch(Throwable t){
                        fail("Failed to update block in payload: '" + ((BuildPayload)tile.build.getPayload()).block() + "'", t);
                    }
                    assertEquals(tile.block(), tile.build.block);
                    assertEquals(tile.block().health, tile.build.health());
                }
            }
        }
    }

    @Test
    void allPayloadBlockTest(){
        int ts = 20;
        Tiles tiles = world.resize(ts * 3, ts * 3);

        world.beginMapLoad();
        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air));
            }
        }

        tiles.getn(tiles.width - 2, tiles.height - 2).setBlock(Blocks.coreShard, Team.sharded);

        Seq<Block> blocks = content.blocks().select(b -> b.canBeBuilt());
        for(int i = 0; i < blocks.size; i++){
            int x = (i % ts) * 3 + 1;
            int y = (i / ts) * 3 + 1;
            Tile tile = tiles.get(x, y);
            tile.setBlock(Blocks.payloadConveyor, Team.sharded);
            Building build = tile.build;
            build.handlePayload(build, new BuildPayload(blocks.get(i), Team.sharded));
        }
        world.endMapLoad();

        checkPayloads();

        SaveIO.write(saveDirectory.child("payloads.msav"));
        logic.reset();
        SaveIO.load(saveDirectory.child("payloads.msav"));

        checkPayloads();
    }

    @TestFactory
    DynamicTest[] testSectorValidity(){
        Seq<DynamicTest> out = new Seq<>();
        if(world == null) world = new World();

        for(SectorPreset zone : content.sectors()){

            out.add(dynamicTest(zone.name, () -> {
                Time.setDeltaProvider(() -> 1f);

                logic.reset();
                state.rules.sector = zone.sector;
                try{
                    world.loadGenerator(zone.generator.map.width, zone.generator.map.height, zone.generator::generate);
                }catch(SaveException e){
                    //fails randomly and I don't care about fixing it
                    e.printStackTrace();
                    return;
                }
                zone.rules.get(state.rules);
                ObjectSet<Item> resources = new ObjectSet<>();
                boolean hasSpawnPoint = false;

                for(Tile tile : world.tiles){
                    if(tile.drop() != null){
                        resources.add(tile.drop());
                    }
                    if(tile.block() instanceof CoreBlock && tile.team() == state.rules.defaultTeam){
                        hasSpawnPoint = true;
                    }
                }

                if(state.rules.waves){
                    Seq<SpawnGroup> spawns = state.rules.spawns;

                    int bossWave = 0;
                    if(state.rules.winWave > 0){
                        bossWave = state.rules.winWave;
                    }else{
                        outer:
                        for(int i = 1; i <= 1000; i++){
                            for(SpawnGroup spawn : spawns){
                                if(spawn.effect == StatusEffects.boss && spawn.getSpawned(i) > 0){
                                    bossWave = i;
                                    break outer;
                                }
                            }
                        }
                    }

                    if(state.rules.attackMode){
                        bossWave = 100;
                    }else{
                        assertNotEquals(0, bossWave, "Sector " + zone.name + " doesn't have a boss/end wave.");
                    }

                    if(state.rules.winWave > 0) bossWave = state.rules.winWave - 1;

                    //TODO check for difficulty?
                    for(int i = 1; i <= bossWave; i++){
                        int total = 0;
                        for(SpawnGroup spawn : spawns){
                            total += spawn.getSpawned(i - 1);
                        }

                        assertNotEquals(0, total, "Sector " + zone + " has no spawned enemies at wave " + i);
                        //TODO this is flawed and needs to be changed later
                        //assertTrue(total < 75, "Sector spawns too many enemies at wave " + i + " (" + total + ")");
                    }
                }

                assertEquals(1, Team.sharded.cores().size, "Sector must have one core: " + zone);

                assertTrue(hasSpawnPoint, "Sector \"" + zone.name + "\" has no spawn points.");
                assertTrue(spawner.countSpawns() > 0 || (state.rules.attackMode && state.rules.waveTeam.data().hasCore()), "Sector \"" + zone.name + "\" has no enemy spawn points: " + spawner.countSpawns());
            }));
        }

        return out.toArray(DynamicTest.class);
    }

    void initBuilding(){
        createMap();

        Tile core = world.tile(5, 5);
        core.setBlock(Blocks.coreShard, Team.sharded, 0);
        for(Item item : content.items()){
            core.build.items.set(item, 3000);
        }

        assertEquals(core.build, Team.sharded.data().core());
    }

    void depositTest(Block block, Item item){
        Unit unit = UnitTypes.mono.create(Team.sharded);
        Tile tile = new Tile(0, 0, Blocks.air, Blocks.air, block);
        tile.setTeam(Team.sharded);
        int capacity = tile.block().itemCapacity;

        assertNotNull(tile.build, "Tile should have an entity, but does not: " + tile);

        int deposited = tile.build.acceptStack(item, capacity - 1, unit);
        assertEquals(capacity - 1, deposited);

        tile.build.handleStack(item, capacity - 1, unit);
        assertEquals(tile.build.items.get(item), capacity - 1);

        int overflow = tile.build.acceptStack(item, 10, unit);
        assertEquals(1, overflow);

        tile.build.handleStack(item, 1, unit);
        assertEquals(capacity, tile.build.items.get(item));
    }

    @Test
    void testUpdateWithNoIndicators(){
        //Ida Update()
        //Arrange
        AttackIndicators attackIndicators = new AttackIndicators();

        //Act
        attackIndicators.update();

        //Assert
        assertTrue(attackIndicators.list().isEmpty());

    }

    @Test
    void testUpdateWithActiveIndicator(){
        //Ida Update()
        //Arrange
        AttackIndicators attackIndicators = new AttackIndicators();
        int x = 1, y = 1;
        attackIndicators.add(x, y);
        long initialIndicator = attackIndicators.list().get(0);

        //Act
        attackIndicators.update();

        //Assert
        long updatedIndicator = attackIndicators.list().get(0);
        assertTrue(updatedIndicator != initialIndicator);
        assertTrue(Indicator.time(updatedIndicator) > Indicator.time(initialIndicator));

    }

    @Test
    void testUpdateRemovesTimedOutIndicator(){
        //Ida Update()
        //Arrange
        AttackIndicators attackIndicators = new AttackIndicators();
        int x = 2, y = 2;
        attackIndicators.add(x, y);

        //Does a for loop to reach timeout
        for(int i = 0; i < 1000; i++){
            attackIndicators.update();
        }

        //Act
        attackIndicators.update();

        //Assert
        assertTrue(attackIndicators.list().isEmpty());

    }

    @Test
    void testUpdateRelocatesIndicesAfterTimeout() {
        // Arrange
        AttackIndicators attackIndicators = new AttackIndicators();
        int x1 = 1, y1 = 1;
        int x2 = 2, y2 = 2;

        //Adding two indicators
        attackIndicators.add(x1, y1);
        attackIndicators.add(x2, y2);

        //Manipulates the time of the first indicator so that it reaches timeout faster
        long[] items = attackIndicators.list().items;
        items[0] = Indicator.time(items[0], 15f * 60f - 1f); //Almost at timeout

        // Act
        attackIndicators.update(); //This should remove the first indicator but leave the second

        // Assert
        assertEquals(1, attackIndicators.list().size); //It should be exactly 1 indicator left

        //Controls that the remaining indicator is correct uppdated in posToIndex
        long remainingIndicator = attackIndicators.list().get(0);
        int remainingPos = Indicator.pos(remainingIndicator);

        assertEquals(Point2.pack(x2, y2), remainingPos);
    }

    @Test
    void testClear() {
        //Ida clear()
        //Arrange
        AttackIndicators attackIndicators = new AttackIndicators();
        attackIndicators.add(1, 1);
        attackIndicators.add(2, 2);
        attackIndicators.add(3, 3);

        //Control if the lists is not empty before clear()
        assertFalse(attackIndicators.list().isEmpty());

        //Act
        attackIndicators.clear();

        //Assert
        //Checks that indicators is empty
        assertTrue(attackIndicators.list().isEmpty());
        //posToIndex is private so then I checks for its effect indirectly
        //Tries to add a new indicator and checks if it works with no problem
        attackIndicators.add(4,4);
        assertEquals(1, attackIndicators.list().size);
    }

    //testAdd_newIndicator tests if the add() method correctly handles the case when a new indicator is created
    @Test
    void testAdd_newIndicator(){
        //Arrange
        int x = 5;
        int y = 10;
        AttackIndicators attackIndicators = new AttackIndicators();

        //make sure there is no previus indicators
        attackIndicators.clear();

        //Act
        attackIndicators.add(x, y);

        //Assert
        //Check that the size of the indiacators have increased by one
        assertEquals(1, attackIndicators.list().size);
        //check that new indicator was added to right position
        int position = Point2.pack(x, y);
        long indicator = attackIndicators.list().get(0);
        //Check if the psoition matches
        assertEquals(position, Indicator.pos(indicator));
        //Check if the added time is correct
        assertEquals(0f, Indicator.time(indicator));
    }

    //testadd_existingindicator tests if an indicator already exists
    @Test
    void testAdd_existingIndicator(){
        //Arrange
        int x = 5;
        int y = 10;
        AttackIndicators attackIndicators = new AttackIndicators();

        //make sure there is no previus indicators
        attackIndicators.clear();

        //Act
        attackIndicators.add(x, y);

        //Set time of the added indicator to simulate an existing one
        long firstIndicator = attackIndicators.list().get(0);
        attackIndicators.list().set(0, Indicator.time(firstIndicator, 5f));

        //Act
        attackIndicators.add(x, y);

        //Assert
        //Check that size of the indicators remain the same
        assertEquals(1, attackIndicators.list().size);

        //Check that existing indicator time is reset
        long indicator = attackIndicators.list().get(0);
        assertEquals(0f, Indicator.time(indicator));
    }



}
