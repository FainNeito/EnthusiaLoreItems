"""A prior delivered sword cannot release or block the next runtime delivery."""

import re
import sqlite3
import unittest
from pathlib import Path


class ProtectionDeliveryWaitTest(unittest.TestCase):
    def test_wait_targets_latest_instance_even_with_old_confirmed_inventory(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/wp05-protection-acceptance.yml"
        match = re.search(r'wait_delivered\(\).*?wait_sql "([^"]+)"', workflow.read_text(encoding="utf8"))
        if match is None:
            raise AssertionError("Protection delivery query is missing")
        query = match.group(1).replace("$DEF", "sword").replace("$player_uuid", "player-id")
        with sqlite3.connect(":memory:") as database:
            database.executescript("""
                create table lore_instances(instance_id text, definition_id text, lifecycle_state text, created_at integer);
                create table instance_current_state(instance_id text, location_type text, location_key text, state text);
                insert into lore_instances values ('old', 'sword', 'ACTIVE', 1);
                insert into lore_instances values ('new', 'sword', 'ACTIVE', 2);
                insert into instance_current_state values ('old', 'PLAYER_INVENTORY', 'player:player-id', 'CONFIRMED_NOW');
            """)
            self.assertEqual(0, database.execute(query).fetchone()[0])
            database.execute("insert into instance_current_state values ('new', 'PLAYER_INVENTORY', 'player:player-id', 'CONFIRMED_NOW')")
            self.assertEqual(1, database.execute(query).fetchone()[0])
            database.execute("update instance_current_state set location_key='player:other' where instance_id='new'")
            self.assertEqual(0, database.execute(query).fetchone()[0])


if __name__ == "__main__":
    unittest.main()
