import arc.Core;
import arc.scene.ui.layout.Table;
import arc.util.I18NBundle;
import mindustry.entities.abilities.Ability;
import mindustry.gen.Unit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AbilityTest {

    private Ability ability;
    private Unit mockUnit;
    private Table mockTable;
    private I18NBundle mockBundle;

    @BeforeEach
    void setUp() {
        ability = new Ability() {
            @Override
            public void update(Unit unit) {
                super.update(unit);
            }
        };
        mockUnit = mock(Unit.class);
        mockTable = mock(Table.class);

        mockBundle = mock(I18NBundle.class);
        Core.bundle = mockBundle;
    }

    @Test
    void testDisplayBars() {
        ability.displayBars(mockUnit, mockTable);

        verifyNoInteractions(mockUnit, mockTable);
    }

    @Test
    void testAbilityStat() {
        String stat = "testStat";
        Object[] values = {1, 2, 3};
        String expectedOutput = "formattedString";

        when(mockBundle.format("ability.stat." + stat, values)).thenReturn(expectedOutput);

        String result = ability.abilityStat(stat, values);

        assertEquals(expectedOutput, result);
    }

    @Test
    void testCopyCreatesNewInstance() {
        Ability copiedAbility = ability.copy();
        assertNotSame(ability, copiedAbility);
    }
}
