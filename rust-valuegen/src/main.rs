#![warn(clippy::pedantic)]
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    clippy::too_many_lines
)]
use clap::Parser;
use serde::{Deserialize, Serialize};
use serde_json::{Number, Value};
use std::{collections::HashMap, fmt::Debug, fs, path::PathBuf};

#[derive(Parser, Debug)]
#[command(
    author = "walksanator",
    version = "v1.0.0",
    about = "generates EMC values",
    long_about = "generates EMC values from a list of hard values, and a json of every recipe type dumped from EMI"
)]
struct Args {
    /// Name of the person to greet
    #[arg(required(true))]
    hard: PathBuf,
    #[arg(required(true))]
    recipes: PathBuf,
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Hash, Clone)]
struct Identifier {
    namespace: String,
    path: String,
}

impl PartialOrd for Identifier {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl ToString for Identifier {
    fn to_string(&self) -> String {
        format!("{}:{}", self.namespace, self.path)
    }
}

impl Ord for Identifier {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.to_string().cmp(&other.to_string())
    }
}

impl From<(&str, &str)> for Identifier {
    fn from(value: (&str, &str)) -> Self {
        Identifier {
            namespace: value.0.to_string(),
            path: value.1.to_string(),
        }
    }
}
impl From<&str> for Identifier {
    fn from(value: &str) -> Self {
        let (namespace, path) = value.split_once(':').unwrap();
        Identifier {
            namespace: namespace.to_string(),
            path: path.to_string(),
        }
    }
}
impl From<String> for Identifier {
    fn from(value: String) -> Self {
        let (namespace, path) = value.split_once(':').unwrap();
        Identifier {
            namespace: namespace.to_string(),
            path: path.to_string(),
        }
    }
}

impl Debug for Identifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.namespace, self.path)
    }
}

#[derive(Serialize, Deserialize, Clone)]
struct Ingredient {
    id: Identifier,
    ammount: u64,
    chance: f32,
}

impl Debug for Ingredient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{:?} x{} {}%",
            self.id,
            self.ammount,
            self.chance * 100f32
        )
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
struct Recipe {
    id: Identifier,
    r#type: Identifier,
    input: Vec<Ingredient>,
    output: Vec<Ingredient>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
struct RecipeNoId {
    r#type: Identifier,
    input: Vec<Ingredient>,
    output: Vec<Ingredient>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
struct TagMap {
    id: Identifier,
    contents: Vec<Identifier>,
}
impl From<Recipe> for TagMap {
    fn from(value: Recipe) -> Self {
        TagMap {
            id: value.id,
            contents: value.input.iter().map(|x| x.id.clone()).collect(),
        }
    }
}

fn main() {
    let args = Args::parse();
    let recipes_str = fs::read_to_string(args.recipes).unwrap();
    let recipes_unfilt: Vec<Recipe> = serde_json::from_str(recipes_str.as_str()).unwrap();

    let recipes_and_tags = recipes_unfilt
        .clone()
        .into_iter()
        .filter(|r| r.r#type != "emi:fuel".into())
        .filter(|r| r.r#type != "emi:composting".into())
        .filter(|r| r.r#type != "emi:world_interaction".into())
        .filter(|r| r.r#type != "emi:anvil_repairing".into())
        .filter(|r| r.r#type != "emi:grinding".into())
        .filter(|r| r.r#type.namespace != *"emi_loot")
        .partition(|r| r.r#type != "emi:tag".into());

    let recipes: Vec<Recipe> = recipes_and_tags.0;
    let tags: Vec<TagMap> = recipes_and_tags
        .1
        .into_iter()
        .filter(|r| r.id != "emi:/tag/item/minecraft/axes".into())
        .filter(|r| r.id != "emi:/tag/item/minecraft/hoes".into())
        .filter(|r| r.id != "emi:/tag/item/minecraft/pickaxes".into())
        .filter(|r| r.id != "emi:/tag/item/minecraft/shovels".into())
        .filter(|r| r.id != "emi:/tag/item/minecraft/swords".into())
        .filter(|r| r.id != "emi:/tag/item/minecraft/tools".into())
        .map(Into::into)
        .collect();
    let mut reverse_tag_lookup: HashMap<Identifier, Vec<Identifier>> = HashMap::new();
    for tagmap in &tags {
        for tag in &tagmap.contents {
            reverse_tag_lookup
                .entry(tag.clone())
                .or_default()
                .push(tagmap.id.clone());
        }
    }
    let mut tag_lookup = HashMap::new();
    for tagmap in tags {
        tag_lookup.insert(tagmap.id, tagmap.contents);
    }
    println!("all tags {tag_lookup:?}");
    let mut items: Vec<Identifier> = Vec::new();
    for recipe in &recipes {
        let filtered: Vec<Identifier> = recipe
            .input
            .iter()
            .filter(|x| !items.contains(&x.id))
            .map(|x| x.id.clone())
            .collect();
        for inp in filtered {
            items.push(inp);
        }
        let refiltered: Vec<Identifier> = recipe
            .output
            .iter()
            .filter(|x| !items.contains(&x.id))
            .map(|x| x.id.clone())
            .collect();
        for out in refiltered {
            items.push(out);
        }
    }
    items.sort();
    items.dedup();

    let mut recipes_map: HashMap<Identifier, RecipeNoId> = HashMap::new();
    for recipe in recipes.clone() {
        recipes_map.insert(
            recipe.id,
            RecipeNoId {
                r#type: recipe.r#type,
                input: recipe.input,
                output: recipe.output,
            },
        );
    }
    let mut items_recipe_map: HashMap<Identifier, Vec<Identifier>> = HashMap::new();
    for (id, recipe) in &recipes_map {
        for output in &recipe.output {
            items_recipe_map
                .entry(output.id.clone())
                .or_default()
                .push(id.clone());
        }
    }

    let hard_pre = fs::read_to_string(args.hard).unwrap();
    let hard_json: HashMap<String, u64> = serde_json::from_str(hard_pre.as_str()).unwrap();
    let mut hard_values: HashMap<Identifier, u64> = HashMap::new();
    for (k, v) in hard_json {
        let id = k.into();
        hard_values.insert(id, v);
    }

    let mut emc_map: HashMap<Identifier, u64> = HashMap::new();
    emc_map.extend(hard_values);
    emc_map.insert("emi:empty".into(), 0); //emi:empty is a dummy created during serialization. in reality it is air, and therefore value 0

    let mut missing_values: Vec<Identifier> = items
        .into_iter()
        .filter(|x| !emc_map.contains_key(x))
        .collect();
    let og_missing = missing_values.clone();
    //okay so... we have a list of all items without EMC
    //and a map of all items with EMC values

    //note of usefull variables by this point
    // missing_values: a list of all items missing values
    // emc_map: a map of all itemid, emc value combinations
    // item_recipe_map: a map of [itemid] = [recipeid...], all recipies that make this item
    // recipe_map: a map of recipeid = recipe
    // tag_lookup: a map of [tag] = [itemid...], each tag points to a list of all item ids in that tag
    // reverse_tag_lookup: a map of [itemid] = [tag...], each itemid points to a list of all tags that item is in

    let mut meta_items_given_emc = 1;
    while meta_items_given_emc > 0 {
        println!("Meta Loop: started");
        meta_items_given_emc = 0;
        let mut items_given_emc = 1;
        while items_given_emc > 0 {
            println!("Recipe Loop: Started");
            items_given_emc = 0;
            //it may have changed from previous iteration
            missing_values.retain(|x| !emc_map.contains_key(x));
            for item in missing_values.clone() {
                let item_recipes = items_recipe_map.get(&item);
                if let Some(recipe_ids) = item_recipes {
                    for id in recipe_ids {
                        let recipe = recipes_map.get(id).unwrap();
                        let mut accu = 0;
                        let mut failed = false;
                        for item in &recipe.input {
                            if let Some(emc) = emc_map.get(&item.id) {
                                accu += emc;
                            } else {
                                failed = true;
                                break;
                            }
                        }
                        if failed {
                            continue;
                        }
                        let mut output_counts = 0;
                        let mut output_emc = 0;
                        let mut my_slot: Option<Ingredient> = None;
                        for ingr in &recipe.output {
                            if let Some(emc) = emc_map.get(&ingr.id) {
                                output_emc += emc;
                            } else if (ingr.id == item)
                                && (ingr.chance
                                    > my_slot
                                        .as_ref()
                                        .unwrap_or(&Ingredient {
                                            id: "null:null".into(),
                                            ammount: 0,
                                            chance: 0.0,
                                        })
                                        .chance)
                            {
                                my_slot = Some(ingr.clone());
                                output_counts += ingr.ammount;
                            } else {
                                output_counts += ingr.ammount;
                            }
                        }
                        if output_counts == 0 {
                            continue;
                        }
                        accu -= output_emc;
                        let per_output_cost = accu / output_counts;
                        let per_output_cost_weighted = if let Some(ingr) = my_slot {
                            f64::floor((per_output_cost as f64) * (1f64 / f64::from(ingr.chance)))
                                as u64
                        } else {
                            per_output_cost
                        };
                        if let Some(emc) = emc_map.get(&item) {
                            if &per_output_cost < emc {
                                emc_map.insert(item.clone(), per_output_cost_weighted);
                            }
                        } else {
                            emc_map.insert(item.clone(), per_output_cost_weighted);
                            items_given_emc += 1;
                            meta_items_given_emc += 1;
                        }
                    }
                }
            }
            println!("Recipe Loop: derived {items_given_emc} values");
        }
        items_given_emc = 1;
        while items_given_emc > 0 {
            println!("Tag Spreading: loop started");
            missing_values.retain(|x| !emc_map.contains_key(x));
            items_given_emc = 0;
            for item in &missing_values {
                println!("trying tag lookup EMC for {item:?}");
                if let Some(tags) = reverse_tag_lookup.get(item) {
                    let mut by_size: Vec<Vec<Identifier>> = tags
                        .iter()
                        .map(|id| tag_lookup.get(id).unwrap().clone())
                        .collect();
                    by_size.sort_by_key(Vec::len);
                    by_size.reverse();
                    let mut success = false;
                    while !success {
                        println!("new tag");
                        if let Some(items) = by_size.pop() {
                            let mut accu = 0;
                            let mut count = 0;
                            for item in items {
                                if let Some(emc) = emc_map.get(&item) {
                                    println!("pulling some EMC from {item:?}");
                                    accu += emc;
                                    count += 1;
                                }
                            }
                            if !(accu == 0 || count == 0) {
                                println!("gave {item:?} a EMC value via tags");
                                emc_map.insert(item.clone(), accu / count);
                                success = true;
                                items_given_emc += 1;
                                meta_items_given_emc += 1;
                            }
                        } else {
                            break;
                        }
                    }
                }
            }
            println!("Tag Spreading: spread {items_given_emc} values");
        }
        println!("Meta loop: affected {meta_items_given_emc} items");
    }
    let mut total = 0;
    let mut given = 0;
    for item in &og_missing {
        if let Some(emc) = emc_map.get(item) {
            println!("gave {item:?} a EMC value of {emc}");
            given += 1;
        } else {
            println!("was unnable to derive EMC for {item:?}");
        }
        total += 1;
    }
    println!("out of {total} items {given} recieved EMC values");
    missing_values.retain(|x| !emc_map.contains_key(x));
    println!("please manually give {missing_values:?} EMC values");

    let mut output_map = serde_json::Map::new();
    for key in emc_map.iter().filter(|x| og_missing.contains(x.0)) {
        output_map.insert(
            key.0.to_string(),
            Value::Number(Number::from(*key.1 as usize)),
        );
    }
    let _ = std::fs::write(
        "new_values.json",
        serde_json::to_string_pretty(&output_map).unwrap(),
    );
}
