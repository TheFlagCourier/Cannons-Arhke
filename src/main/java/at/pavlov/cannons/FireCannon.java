package at.pavlov.cannons;

import java.util.Iterator;
import java.util.List;

import at.pavlov.cannons.Enum.BreakCause;
import at.pavlov.cannons.event.CannonFireEvent;
import at.pavlov.cannons.event.CannonUseEvent;
import at.pavlov.cannons.Enum.InteractAction;
import at.pavlov.cannons.utils.DelayedTask;
import at.pavlov.cannons.utils.FireTaskWrapper;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonDesign;
import at.pavlov.cannons.config.Config;
import at.pavlov.cannons.cannon.DesignStorage;
import at.pavlov.cannons.Enum.MessageEnum;
import at.pavlov.cannons.projectile.Projectile;
import at.pavlov.cannons.projectile.ProjectileProperties;

public class FireCannon {
	
	private final Config config;
	private final DesignStorage designStorage;
	private final Cannons plugin;
	private final CreateExplosion explosion;

	
	
	
	public FireCannon(Cannons plugin, Config config, CreateExplosion explosion)
	{
		this.plugin = plugin;
		this.config = config;
		this.designStorage = plugin.getDesignStorage();
		this.explosion = explosion;
	}

    /**
     * fires additionally the cannon fire event when the cannon is fired
     * @param cannon - cannon to fire
     * @param player - operator of the cannon
     * @param action - how has the player/plugin interacted with the cannon
     * @return - message for the player
     */
    public MessageEnum fireCannonAndEvents(Cannon cannon, Player player, InteractAction action)
    {
        CannonDesign design = cannon.getCannonDesign();

        //fire event
        CannonUseEvent useEvent = new CannonUseEvent(cannon, player, action);
        Bukkit.getServer().getPluginManager().callEvent(useEvent);

        if (useEvent.isCancelled())
            return null;

        boolean autoreload = player.isSneaking() && player.hasPermission(design.getPermissionAutoreload());

        return this.prepareFire(cannon, player, autoreload, !design.isAmmoInfiniteForPlayer());
    }

	
	/**
	 * checks all condition but does not fire the cannon
	 * @param cannon
	 * @param player
	 * @return
	 */
    MessageEnum getPrepareFireMessage(Cannon cannon, Player player)
	{
		CannonDesign design = cannon.getCannonDesign();
		if (design == null) return null;
		//if the player is not the owner of this gun
		if (player != null && !cannon.getOwner().equals(player.getName())  && design.isAccessForOwnerOnly())
			return MessageEnum.ErrorNotTheOwner;
		//check if there is some gunpowder in the barrel
		if (cannon.getLoadedGunpowder() <= 0)
			return MessageEnum.ErrorNoGunpowder;//is there a projectile
		if(!cannon.isLoaded())
            return MessageEnum.ErrorNoProjectile;
        //Firing in progress
        if (cannon.isFiring())
            return MessageEnum.ErrorFiringInProgress;
		//Barrel too hot
        if(cannon.getLastFired() + design.getBarrelCooldownTime()*1000 >= System.currentTimeMillis())
			return MessageEnum.ErrorBarrelTooHot;
        //automatic temperature control, prevents overheating of the cannon
        if(design.isAutomaticTemperatureControl() && cannon.getTemperature() + design.getHeatIncreasePerGunpowder()*cannon.getLoadedGunpowder() > design.getCriticalTemperature())
            return MessageEnum.ErrorBarrelTooHot;
		if (player!= null)
		{
		
			//if the player has permission to fire
			if (!player.hasPermission(design.getPermissionFire()))
				return MessageEnum.PermissionErrorFire;

			//check if the player has the permission for this projectile
			Projectile projectile = cannon.getLoadedProjectile();
			if(projectile != null && !projectile.hasPermission(player))
				return MessageEnum.PermissionErrorProjectile;
			//check for flint and steel
           if (design.isFiringItemRequired() && !config.getToolFiring().equalsFuzzy(player.getItemInHand()) )
				return MessageEnum.ErrorNoFlintAndSteel;
		}
		//everything fine fire the damn cannon
		return MessageEnum.CannonFire;
	}
	
	/**
	 * checks if all preconditions for firing are fulfilled and fires the cannon
	 * @param cannon - the cannon which is fired
	 * @param player - player operating the cannon
	 * @param autoload - the cannon will autoreload before firing
     * @param consumesAmmo - if true ammo will be removed from chest inventories
	 * @return - message for the player
	 */
	public MessageEnum prepareFire(Cannon cannon, Player player, boolean autoload, boolean consumesAmmo)
	{
        Projectile projectile = cannon.getLoadedProjectile();
        //no charge no firing
        if (cannon.getLoadedGunpowder() == 0 || projectile == null)
        {
            //this cannon will try to find some gunpowder and projectile in the chest
            if (autoload)
            {
                boolean hasReloaded =  cannon.reloadFromChests(player, consumesAmmo);
                if (!hasReloaded)
                {
                    //there is not enough gunpowder or no projectile in the chest
                    plugin.logDebug("Can't reload cannon, because there is no valid charge in the chests");
                    if (cannon.getLoadedGunpowder() > 0)
                        return MessageEnum.ErrorNoProjectile;
                    return MessageEnum.ErrorNoGunpowder;
                }
                else
                {
                    plugin.logDebug("Charge loaded from chest");
                }
            }
            else
            {
                //can't fire without charge
                plugin.logDebug("Can't fire without a charge");
                if (cannon.getLoadedGunpowder() > 0)
                    return MessageEnum.ErrorNoProjectile;
                return MessageEnum.ErrorNoGunpowder;
            }
        }

		//check for all permissions
		MessageEnum message = getPrepareFireMessage(cannon, player);
		
		//return if there are permission missing
		if (message != MessageEnum.CannonFire)
            return message;

        CannonFireEvent fireEvent = new CannonFireEvent(cannon, player);
        Bukkit.getServer().getPluginManager().callEvent(fireEvent);

        if (fireEvent.isCancelled())
            return null;

		//ignite the cannon
		delayedFire(cannon, player);
		
		return message;
	}
	
	/**
	 * delays the firing by the fuse burn time
	 * @param cannon
	 * @param player
	 */
    private void delayedFire(Cannon cannon, Player player)
    {
    	CannonDesign design = designStorage.getDesign(cannon);
        Projectile projectile = cannon.getLoadedProjectile();
    	
		//reset after firing
		cannon.setLastFired(System.currentTimeMillis());
		
		//Set up smoke effects on the torch
		for (Location torchLoc : design.getFiringIndicator(cannon))
		{
			torchLoc.setX(torchLoc.getX() + 0.5);
			torchLoc.setY(torchLoc.getY() + 1);
			torchLoc.setZ(torchLoc.getZ() + 0.5);
			torchLoc.getWorld().playEffect(torchLoc, Effect.SMOKE, BlockFace.UP);
			torchLoc.getWorld().playSound(torchLoc, Sound.FUSE , 5, 1);
		}

        //this cannon is now firing
        cannon.setFiring(true);
		
		//set up delayed task with automatic firing. Several bullets with time delay for one loaded projectile
        for(int i=0; i < projectile.getAutomaticFiringMagazineSize(); i++)
        {
            Long delayTime = (long) (design.getFuseBurnTime() * 20.0 + i*projectile.getAutomaticFiringDelay()*20.0);
            Object fireTask = new FireTaskWrapper(cannon, player, i==(projectile.getAutomaticFiringMagazineSize()-1));
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new DelayedTask(fireTask)
            {
                public void run(Object object)
                    {
                        FireTaskWrapper fireTask = (FireTaskWrapper) object;
                        fire(fireTask.getCannon(), fireTask.getPlayer(), fireTask.isRemoveCharge());
                    }
            }, delayTime);
        }
    }
	
	/**
	 * fires a cannon and removes the charge from the player
	 * @param cannon - the fired cannon
     * @param shooter - the player firing the cannon
     * @param removeCharge - if the charge is removed after firing
	 */
    private void fire(Cannon cannon, Player shooter, boolean removeCharge)
    {
        CannonDesign design = cannon.getCannonDesign();
        Projectile projectile = cannon.getLoadedProjectile();

        // no projectile no cannon firing
        if (projectile == null) return;
        // no gunpowder no cannon firing
        if (cannon.getLoadedGunpowder() <= 0) return;


        Location firingLoc = design.getMuzzle(cannon);
        World world = cannon.getWorldBukkit();

        //Muzzle flash + effects
        if (projectile.getProjectileEntity().equals(EntityType.ARROW))
            world.playEffect(firingLoc, Effect.BOW_FIRE, 10);
        else
            world.createExplosion(firingLoc, 0F, false);
        world.playEffect(firingLoc, Effect.SMOKE, cannon.getCannonDirection());

        //increase heat of the cannon
        cannon.setTemperature(cannon.getTemperature()+design.getHeatIncreasePerGunpowder()*cannon.getLoadedGunpowder());
        //automatic cool down
        if (design.isAutomaticCooling())
            cannon.automaticCoolingFromChest(shooter);

        //for each bullet, but at least once
        for (int i=0; i < Math.max(projectile.getNumberOfBullets(), 1); i++)
        {
            Vector vect = cannon.getFiringVector(shooter);
            org.bukkit.entity.Projectile projectileEntity = plugin.getProjectileManager().spawnProjectile(projectile, shooter, firingLoc, vect);

            if (i == 0 && projectile.hasProperty(ProjectileProperties.SHOOTER_AS_PASSENGER))
                projectileEntity.setPassenger(shooter);

            //confuse all entity which wear no helmets due to the blast of the cannon
            List<Entity> living = projectileEntity.getNearbyEntities(8, 8, 8);
            //do only once
            if (i == 0)
            {
                confuseShooter(living, firingLoc, design.getBlastConfusion());
            }
        }

        //reset after firing
        cannon.setLastFired(System.currentTimeMillis());

        //check if the temperature exceeds the limit
        boolean isDestroyed = cannon.checkHeatManagement();
        if (isDestroyed)
        {
            plugin.getCannonManager().removeCannon(cannon, true, BreakCause.Overheating);
            return;
        }


        if (removeCharge)
        {
            //finished firing
            cannon.setFiring(false);

            //redstone or player infinite ammo will not remove the charge
            //if ((shooter == null && !cannon.getCannonDesign().isAmmoInfiniteForRedstone()))
            //{
            plugin.logDebug("fire event complete, charge removed from the cannon");
            //removes the gunpowder and projectile loaded in the cannon
            cannon.removeCharge();
            //}
        }
    }



    
    /**
     * confuses an entity to simulate the blast of a cannon
     * @param living
     * @param confuseTime
     */
    private void confuseShooter(List<Entity> living, Location firingLoc, double confuseTime)
    {
    	 //confuse shooter if he wears no helmet (only for one projectile and if its configured)
    	if (confuseTime > 0)
    	{
    		Iterator<Entity> iter = living.iterator();
    		while (iter.hasNext())
    		{
    			boolean harmEntity = false;
    			Entity next = iter.next();
    			if (next instanceof Player)
    			{
    				
    				Player player = (Player) next;
    				if ( player.isOnline() && !CheckHelmet(player) && player.getGameMode() != GameMode.CREATIVE  )
    				{
    					//if player has no helmet and is not in creative and there are confusion effects - harm him
    					harmEntity = true;		
    				}
    			}
    			else if (next instanceof LivingEntity)
    			{
    				//damage entity
    				harmEntity = true;
    			}
    			if (harmEntity)
    			{
    				//damage living entities and unprotected players
    				LivingEntity livingEntity = (LivingEntity) next;
                    if (livingEntity.getLocation().distance(firingLoc) < 5.0)
    				    livingEntity.damage(1);
    				livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION,(int) confuseTime*20, 0));

    			}
    		}
    	}
    }
    

		
		
    //############## CheckHelmet   ################################
	private boolean CheckHelmet(Player player)
	{
		ItemStack helmet = player.getInventory().getHelmet();
        return helmet != null;
    }
}
