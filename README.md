Simple mod for Wurm Unlimited that enables managing branded animal permissions &
inventories on PvP servers


Patches the following methods:
 * com.wurmonline.server.creatures.Brand#addInitialPermissions
 * com.wurmonline.server.creatures.Creature#canHavePermissions
 * com.wurmonline.server.creatures.Creatures#getManagedAnimalsFor
 * com.wurmonline.server.behaviours.ManageMenu#getBehavioursFor
 * com.wurmonline.server.behaviours.ManageMenu#action
 * com.wurmonline.server.behaviours.CreatureBehaviour#addVehicleOptions
 * com.wurmonline.server.behaviours.CreatureBehaviour#action
 * com.wurmonline.server.creatures.Communicator#reallyHandle_CMD_MOVE_INVENTORY
