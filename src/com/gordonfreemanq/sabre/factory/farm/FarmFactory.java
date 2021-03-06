package com.gordonfreemanq.sabre.factory.farm;

import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.gordonfreemanq.sabre.SabrePlayer;
import com.gordonfreemanq.sabre.SabrePlugin;
import com.gordonfreemanq.sabre.factory.BaseFactory;
import com.gordonfreemanq.sabre.factory.FactoryWorker;
import com.gordonfreemanq.sabre.factory.recipe.FarmRecipe;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;

/**
 * Class that provides core functionality for all the farm factories
 * @author GFQ
 *
 */
public class FarmFactory extends BaseFactory {
	
	// How many factory ticks to wait before producing
	private final int farmProductionTicks;
	
	// How far away it has to be away from another running farm
	private final int farmProximity;
	
	// How many minutes between each survey
	private final int surveyPeriodMin;
	
	// The current production tick counter for when the farm is running
	private int farmTickCounter;
	
	// The crop coverage from 0 to 1.0
	private double layoutFactor;
	
	// Efficiency factory based on proximity to other farms from 0 to 1.0
	private double proximityFactor;
	
	// Chunk fertility factor
	private double fertilityFactor;
	
	// Biome efficiency factor
	private double biomeFactor;
	
	// The amount of farmed goods
	private HashMap<CropType, Integer> farmedCrops;
	
	// The farm surveyor
	private FarmSurveyor surveyor;
	
	private Date lastSurvey;
	
	// The most crops the factory can hold at a time
	private int storageSize;
	
	// The location of the nearest farm that affects proximity factor
	protected Location proximityLimitLocation;

	/**
	 * Creates a new FarmFactory instance
	 * @param location The factory location
	 * @param typeName The factory name
	 */
	public FarmFactory(Location location, String typeName) {
		super(location, typeName);
		this.farmProductionTicks = SabrePlugin.getPlugin().getSabreConfig().getFarmProductionTicks();
		this.farmProximity = SabrePlugin.getPlugin().getSabreConfig().getFarmProximity();
		this.surveyPeriodMin = SabrePlugin.getPlugin().getSabreConfig().getFarmSurveyPeriod();
		this.farmTickCounter = 0;
		this.layoutFactor = 0.0;
		this.proximityFactor = 0.0;
		this.fertilityFactor = 0.0;
		this.biomeFactor = 0.0;
		this.farmedCrops = new HashMap<CropType, Integer>();
		this.lastSurvey = new Date(0);
		this.storageSize = 64;
		readCustomConfig();
	}
	
	
	/**
	 * Reads the custom farm config
	 */
	private void readCustomConfig() {
		ConfigurationSection farmConfig = this.properties.getCustomConfig();
		if (farmConfig != null) {
			this.storageSize = farmConfig.getInt("storage_size", storageSize);
		}
	}
	
	/**
	 * Handles hitting the block with a stick
	 * @param p The player interacting
	 */
	@Override
	public void onStickInteract(PlayerInteractEvent e, SabrePlayer sp) {
		
		Action a = e.getAction();
		if (a == Action.RIGHT_CLICK_BLOCK) {
			if (running) {
				if (recipe instanceof FarmRecipe) {
					FarmRecipe fr = (FarmRecipe)recipe;
					sp.msg("<a>Status: <n>Farming %s, %d ready for harvest", fr.getCrop().toString(), 
							this.farmedCrops.get(((FarmRecipe) recipe).getCrop()));

				} else {
					sp.msg("<a>Status: <n>%d%%", this.getPercentComplete());
				}
			} else {
				cycleRecipe(sp);
				checkSurvey(true);
			}
		} else {
			if (!running && farmIsFull() && (recipe instanceof FarmRecipe)) {
				sp.msg("<i>The farm is already full.");
			} else {
				cyclePower(sp);
			}

		}
		
		// Create a controller
		if (this.canPlayerModify(sp)) {
			createController(sp);
		}
	}
	
	@Override
	protected void onPowerOn() {
		checkSurvey(true);
		saveSettings();
	}
	
	@Override
	protected void onPowerOff() {
		saveSettings();
	}
	
	
	/**
	 * Creates the factory controller
	 * @param sp The player
	 */
	@Override
	public void createController(SabrePlayer sp) {
		ItemStack is = (new FarmController(this)).toItemStack();
		sp.getPlayer().getInventory().setItemInHand(is);
	}
	
	
	/**
	 * Update method for running the factory
	 */
	@Override
	public void update() {
		
		if (!running) {
			return;
		}
		
		checkSurvey(false);
		if (recipe instanceof FarmRecipe) {
			if (farmTickCounter++ >= farmProductionTicks) {
				farmTickCounter = 0;
				runFarmRecipe();
				
				if (farmIsFull()) {
					powerOff();
				}
			}
		} else {
			super.update();
		}
	}
	
	protected void runFarmRecipe() {
		if (!running) {
			return;
		}
		
		if (!(recipe instanceof FarmRecipe)) {
			return;
		}
		
		FarmRecipe fr = (FarmRecipe)recipe;
		
		// Just a quick sanity check on these numbers
		this.layoutFactor = Math.max(Math.min(layoutFactor, 1.0), 0);
		this.proximityFactor = Math.max(Math.min(proximityFactor, 1.0), 0);
		this.biomeFactor = Math.max(Math.min(biomeFactor, 1.0), 0);
		this.fertilityFactor = Math.max(fertilityFactor, 0);
		
		CropType cropType = fr.getCrop();
		int alreadyFarmed = farmedCrops.get(cropType);
		int total = alreadyFarmed + this.getRealOutput();
		
		// Limit the output to the storage size of the factory
		total = Math.min(this.storageSize, total);
		farmedCrops.put(cropType, total);
		//SabrePlugin.getPlugin().log(Level.INFO, "Doing farm recipe - %d crops", total);
		
		// Power off zero-output factories so they don't run forever
		if (this.getRealOutput() == 0) {
			powerOff();
		}
		
		// Power the factory off if it's full
		if (total >= storageSize) {
			powerOff();
		}
		
		saveSettings();
	}
	
	
	/**
	 * Checks if the farm is full
	 * @return true if it is full
	 */
	protected boolean farmIsFull() {
		
		int totalFarmed = 0;
		for(Entry<CropType, Integer> e : this.farmedCrops.entrySet()) {
			totalFarmed += e.getValue();
		}
		
		if (totalFarmed >= this.storageSize) {
			return true;
		}
		
		return false;
	}
	
	
	/**
	 * Calculates the coverage and proximity factor values
	 */
	public void checkSurvey(boolean force) {
		Date now = new Date();
		long timeDiff = now.getTime() - lastSurvey.getTime();
		long diffMin = TimeUnit.MINUTES.convert(timeDiff, TimeUnit.MILLISECONDS);

		calculateProximityFactor();
		if (force || diffMin >= this.surveyPeriodMin) {
			if (survey()) {
				lastSurvey = now;
				saveSettings();
			}
		}
	}
	
	
	/**
	 * Calculates the farm layout factor
	 */
	private boolean survey() {
		if (!(recipe instanceof FarmRecipe)) {
			return false;
		}
		
		// Get the surveyor for the current crop and do the survey
		surveyor = ((FarmRecipe)recipe).getSurveyor();
		surveyor.surveyFarm(this);
		
		this.layoutFactor = surveyor.getCoverageFactor();
		this.fertilityFactor = surveyor.getFertilityFactor();
		this.biomeFactor = surveyor.getBiomeFactor();
		return true;
	}
	
	
	/**
	 * Calculates the farm proximity factor which scales according to how close other farms are
	 */
	private void calculateProximityFactor() {
		double factor = 1.0;
		proximityLimitLocation = null;

		// Iterate through all the other running farms and find out if any other
		// farm factories are within the exclusion zone
		for (BaseFactory f : FactoryWorker.getInstance().getRunningFactories()) {
			if (!(f instanceof FarmFactory)) {
				continue;
			}
			
			FarmFactory farm = (FarmFactory)f;
			
			// Ignore self
			if (f.getLocation().equals(this.location)) {
				continue;
			}
			
			double dist = f.getLocation().distance(this.location);
			//SabrePlugin.getPlugin().log(Level.INFO, "Proximity: other farm at %s", f.getLocation().toString());
			
			// The proximity factor will drop proportionally to each farm that is within
			// the exclusion zone
			if (dist < farmProximity && farm.layoutFactor >= 0.05) {
				double tempFactor = Math.max(0, factor - ((farmProximity - dist) / farmProximity));
				if (tempFactor < factor) {
					factor = tempFactor;
					proximityLimitLocation = f.getLocation();
				}
			}
			
			// No need to go below zero
			if (factor <= 0) {
				factor = 0;
				break;
			}
		}
		
		this.proximityFactor = factor;
	}
	
	
	/**
	 * Gets the farmed crops
	 * @return The farmed crops
	 */
	public HashMap<CropType, Integer> getFarmedCrops() {
		return this.farmedCrops;
	}
	
	
	/**
	 * Clears out the farmed crops
	 */
	public void clearFarmedCrops() {
		farmedCrops.clear();
	}
	
	
    /**
     * Gets the layout factpr percent
     * @return The layout factor percent
     */
    public Long getLayoutFactorPercent() {
    	return Math.round(layoutFactor * 100.0);
    }
    
    
    /**
     * Gets the proximity factor percent
     * @return The layout percent
     */
    public Long getProximityFactorPercent() {
    	return Math.round(proximityFactor * 100.0);
    }
    
    
    /**
     * Gets the fertility factor percent
     * @return The fertility percent
     */
    public Long getFertilityFactorPercent() {
    	return Math.round(fertilityFactor * 100.0);
    }
    
    
    /**
     * Gets the biome factor percent
     * @return The biome percent
     */
    public Long getBiomeFactorPercent() {
    	return Math.round(biomeFactor * 100.0);
    }
    
    
    /**
     * Gets the real output rate
     * @return The real output rate
     */
    public int getRealOutput() {
    	if (!(recipe instanceof FarmRecipe)) {
    		return 0;
    	}
    	
    	FarmRecipe fr = (FarmRecipe)recipe;
    	return (int)(fr.getProductionRate() * layoutFactor * proximityFactor * fertilityFactor * biomeFactor);
    }
    
    /**
     * Gets the nominal output rate
     * @return The nominal output rate
     */
    public int getNominalOutput() {
    	if (!(recipe instanceof FarmRecipe)) {
    		return 0;
    	}
    	
    	FarmRecipe fr = (FarmRecipe)recipe;
    	return fr.getProductionRate();
    }
    
	/**
	 * Gets the settings specific to this block
	 * @return The mongodb document with the settings
	 */
    @Override
	public BasicDBObject getSettings() {
    	BasicDBObject doc = super.getSettings();

		doc = doc.append("last_survey", lastSurvey);
		
		doc = doc.append("layout", this.layoutFactor);
		doc = doc.append("proximity", this.proximityFactor);
		doc = doc.append("fertility", this.fertilityFactor);
		doc = doc.append("biome", this.biomeFactor);
		
		BasicDBList cropList = new BasicDBList();
		for(Entry<CropType, Integer> e : this.farmedCrops.entrySet()) {
			cropList.add(
					new BasicDBObject()
					.append("name", e.getKey().toString())
					.append("count", e.getValue()));
		}
		
		doc = doc.append("crops", cropList);
    	
    	return doc;
	}
    
	/**
	 * Loads settings from a mongodb document
	 * @param o The db document
	 */
	public void loadSettings(BasicDBObject o) {

		this.lastSurvey = o.getDate("last_survey", lastSurvey);
		
		this.layoutFactor = o.getDouble("layout", 0);
		this.proximityFactor = o.getDouble("proximity", 0);
		this.fertilityFactor = o.getDouble("fertility", 0);
		this.biomeFactor = o.getDouble("biome", 0);
		
		if (o.containsField("crops")) {
			BasicDBList cropObject = (BasicDBList)o.get("crops");
			for (Object cropObj : cropObject) {
				BasicDBObject crop = (BasicDBObject)cropObj;
				if (crop.containsField("name")) {
					CropType cropType = CropType.valueOf(crop.getString("name"));
					if (cropType != null) {
						int count = crop.getInt("count", 0);
						this.farmedCrops.put(cropType, count);
					}
				}
			}
		}
		
		super.loadSettings(o);
	}
    
    
    /**
     * Whether the factory should run while unloaded
     * @return
     */
    public boolean runUnloaded() {
    	return (recipe instanceof FarmRecipe);
    }
    
    
    /**
     * Gets the farm recipe if it exists
     * @return The farm recipe
     */
    public FarmRecipe getFarmRecipe() {
    	if (this.recipe instanceof FarmRecipe) {
    		return (FarmRecipe)recipe;
    	}
    	return null;
    }
    

    /**
     * Copies data from another instance
     * @param other The other instance
     */
    @Override
    public void copyFrom(BaseFactory other) {
    	
    	if (other instanceof FarmFactory) {
    		FarmFactory farm = (FarmFactory)other;
    		
    		this.farmTickCounter = farm.farmTickCounter;
    		this.farmTickCounter = farm.farmTickCounter;
    		
    		this.farmTickCounter = farm.farmTickCounter;
    		this.layoutFactor = farm.layoutFactor;
    		this.proximityFactor = farm.proximityFactor;
    		this.fertilityFactor = farm.fertilityFactor;
    		this.biomeFactor = farm.biomeFactor;
    		this.farmedCrops = farm.farmedCrops;
    		this.surveyor = farm.surveyor;
    		this.lastSurvey = farm.lastSurvey;
    		this.storageSize = farm.storageSize;
    		this.proximityLimitLocation = farm.proximityLimitLocation;
    	}
    	
    	super.copyFrom(other);
    }
}
