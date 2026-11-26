#!/usr/bin/env python3
"""Writes the mod's JSON assets and data (blockstates, models, recipes, loot tables, tags).

Run from the mod root:  python3 tools/gen_resources.py
Language files are maintained by hand in assets/bathhouse/lang.
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources')
NS = 'bathhouse'
FACINGS = {'north': 0, 'east': 90, 'south': 180, 'west': 270}

FIREBOXES = ['steel_firebox', 'cast_iron_firebox', 'brick_firebox', 'heating_element']
APPLIANCES = ['steam_generator', 'warm_lounger']
STOVE_PARTS = ['ash_pit', 'closed_heater', 'open_heater', 'boiler', 'water_tank', 'damper', 'fuel_tunnel', 'stove_cladding']
PLUNGE_PARTS = ['plunge_wall_wood', 'plunge_wall_composite', 'plunge_wall_tile', 'plunge_bench', 'plunge_stove',
                'plunge_chiller', 'plunge_thermometer', 'sawdust']


def write(path, obj):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, 'w', encoding='utf-8') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


def variant(model, y):
    v = {'model': model}
    if y:
        v['y'] = y
    return v


def orientable(name, top, front, side):
    return {'parent': 'minecraft:block/orientable', 'textures': {'top': top, 'front': front, 'side': side}}


def tex(name):
    return f'{NS}:block/{name}'


def facing_states(name, prop, models, rotate=0):
    """Blockstate over facing x boolean property; models = {false_model, true_model}."""
    variants = {}
    for facing, y in FACINGS.items():
        for value in ('false', 'true'):
            variants[f'facing={facing},{prop}={value}'] = variant(models[value], (y + rotate) % 360)
    return {'variants': variants}


def assets():
    a = f'assets/{NS}'
    bs = f'{a}/blockstates'
    bm = f'{a}/models/block'
    im = f'{a}/models/item'

    # fireboxes and single appliances: orientable cubes with a lit front
    for name in FIREBOXES + APPLIANCES:
        for suffix in ('', '_on'):
            write(f'{bm}/{name}{suffix}.json', orientable(name, tex(f'{name}_top'), tex(f'{name}_front{suffix}'), tex(f'{name}_side')))
        write(f'{bs}/{name}.json', facing_states(name, 'lit', {'false': f'{NS}:block/{name}', 'true': f'{NS}:block/{name}_on'}))
        write(f'{im}/{name}.json', {'parent': f'{NS}:block/{name}'})

    # stove parts (all have facing + active)
    write(f'{bm}/ash_pit.json', orientable('ash_pit', tex('ash_pit_top'), tex('ash_pit_front'), tex('ash_pit_side')))
    write(f'{bm}/ash_pit_full.json', orientable('ash_pit', tex('ash_pit_top'), tex('ash_pit_front_full'), tex('ash_pit_side')))
    write(f'{bs}/ash_pit.json', facing_states('ash_pit', 'active', {'false': f'{NS}:block/ash_pit', 'true': f'{NS}:block/ash_pit_full'}))

    write(f'{bm}/closed_heater.json', orientable('closed_heater', tex('closed_heater_top'), tex('closed_heater_front'), tex('closed_heater_side')))
    write(f'{bm}/closed_heater_filled.json', orientable('closed_heater', tex('closed_heater_top'), tex('closed_heater_front_filled'), tex('closed_heater_side')))
    write(f'{bs}/closed_heater.json', facing_states('closed_heater', 'active',
                                                     {'false': f'{NS}:block/closed_heater', 'true': f'{NS}:block/closed_heater_filled'}))

    write(f'{bm}/open_heater.json', {'parent': 'minecraft:block/cube_column',
                                     'textures': {'side': tex('open_heater_side_empty'), 'end': tex('open_heater_top_empty')}})
    write(f'{bm}/open_heater_filled.json', {'parent': 'minecraft:block/cube_column',
                                            'textures': {'side': tex('open_heater_side'), 'end': tex('open_heater_top')}})
    write(f'{bs}/open_heater.json', facing_states('open_heater', 'active',
                                                   {'false': f'{NS}:block/open_heater', 'true': f'{NS}:block/open_heater_filled'}))

    for name in ('boiler', 'water_tank'):
        write(f'{bm}/{name}.json', orientable(name, tex(f'{name}_top'), tex(f'{name}_front'), tex(f'{name}_side')))
        write(f'{bs}/{name}.json', facing_states(name, 'active', {'false': f'{NS}:block/{name}', 'true': f'{NS}:block/{name}'}))

    write(f'{bm}/fuel_tunnel.json', orientable('fuel_tunnel', tex('fuel_tunnel_side'), tex('fuel_tunnel_front'), tex('fuel_tunnel_side')))
    write(f'{bs}/fuel_tunnel.json', facing_states('fuel_tunnel', 'active', {'false': f'{NS}:block/fuel_tunnel', 'true': f'{NS}:block/fuel_tunnel'}))

    write(f'{bm}/stove_cladding.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex('stove_cladding')}})
    write(f'{bs}/stove_cladding.json', facing_states('stove_cladding', 'active',
                                                      {'false': f'{NS}:block/stove_cladding', 'true': f'{NS}:block/stove_cladding'}))

    def pillar(texture):
        return {
            'parent': 'minecraft:block/block',
            'textures': {'particle': texture, 'side': texture, 'top': tex('chimney_top')},
            'elements': [{
                'from': [4, 0, 4], 'to': [12, 16, 12],
                'faces': {
                    'north': {'texture': '#side'}, 'south': {'texture': '#side'},
                    'east': {'texture': '#side'}, 'west': {'texture': '#side'},
                    # no bottom face: stacked flues would z-fight at the joint
                    'up': {'texture': '#top', 'cullface': 'up'},
                },
            }],
        }
    write(f'{bm}/damper.json', pillar(tex('damper')))
    write(f'{bm}/damper_open.json', pillar(tex('damper_open')))
    write(f'{bs}/damper.json', facing_states('damper', 'active', {'false': f'{NS}:block/damper', 'true': f'{NS}:block/damper_open'}))

    for name in STOVE_PARTS:
        model = {'closed_heater': 'closed_heater_filled', 'open_heater': 'open_heater_filled', 'damper': 'damper_open'}.get(name, name)
        write(f'{im}/{name}.json', {'parent': f'{NS}:block/{model}'})

    # chimney
    write(f'{bs}/chimney.json', {'variants': {'': {'model': f'{NS}:block/chimney'}}})
    write(f'{bm}/chimney.json', pillar(tex('chimney')))
    write(f'{im}/chimney.json', {'parent': f'{NS}:block/chimney'})

    # thermo-hygrometer: thin plate on the wall
    variants = {}
    for facing, y in FACINGS.items():
        for active in ('false', 'true'):
            model = f'{NS}:block/bath_gauge' + ('_active' if active == 'true' else '')
            variants[f'active={active},facing={facing}'] = variant(model, y)
    write(f'{bs}/bath_gauge.json', {'variants': variants})
    for suffix in ('', '_active'):
        write(f'{bm}/bath_gauge{suffix}.json', {
            'parent': 'minecraft:block/block',
            'render_type': 'minecraft:cutout',
            'textures': {'particle': tex('bath_gauge_side'), 'front': tex(f'bath_gauge_front{suffix}'), 'side': tex('bath_gauge_side')},
            'elements': [{
                'from': [3, 3, 14], 'to': [13, 13, 16],
                'faces': {
                    'north': {'uv': [3, 3, 13, 13], 'texture': '#front'},
                    'south': {'uv': [3, 3, 13, 13], 'texture': '#side', 'cullface': 'south'},
                    'east': {'uv': [0, 3, 2, 13], 'texture': '#side'},
                    'west': {'uv': [14, 3, 16, 13], 'texture': '#side'},
                    'up': {'uv': [3, 14, 13, 16], 'texture': '#side'},
                    'down': {'uv': [3, 0, 13, 2], 'texture': '#side'},
                },
            }],
        })
    write(f'{im}/bath_gauge.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': tex('bath_gauge_front')}})

    # smoke window
    write(f'{bs}/smoke_vent.json', {'variants': {
        'open=false': {'model': f'{NS}:block/smoke_vent'},
        'open=true': {'model': f'{NS}:block/smoke_vent_open'},
    }})
    for suffix in ('', '_open'):
        write(f'{bm}/smoke_vent{suffix}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex(f'smoke_vent{suffix}')}})
    write(f'{im}/smoke_vent.json', {'parent': f'{NS}:block/smoke_vent'})

    # plunge parts
    write(f'{bm}/plunge_wall_wood.json', {'parent': 'minecraft:block/cube_column',
                                          'textures': {'side': tex('plunge_wall_wood'), 'end': tex('plunge_wall_wood_top')}})
    write(f'{bm}/plunge_wall_composite.json', {'parent': 'minecraft:block/cube_column',
                                               'textures': {'side': tex('plunge_wall_composite'), 'end': tex('plunge_wall_composite_top')}})
    write(f'{bm}/plunge_wall_tile.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex('plunge_wall_tile')}})
    for name in ('plunge_wall_wood', 'plunge_wall_composite', 'plunge_wall_tile'):
        write(f'{bs}/{name}.json', {'variants': {'': {'model': f'{NS}:block/{name}'}}})

    write(f'{bm}/plunge_bench.json', {'parent': 'minecraft:block/slab',
                                      'textures': {'bottom': tex('plunge_bench'), 'top': tex('plunge_bench'), 'side': tex('plunge_bench')}})
    write(f'{bs}/plunge_bench.json', {'variants': {
        'waterlogged=false': {'model': f'{NS}:block/plunge_bench'},
        'waterlogged=true': {'model': f'{NS}:block/plunge_bench'},
    }})

    for suffix in ('', '_on'):
        write(f'{bm}/plunge_stove{suffix}.json', orientable('plunge_stove', tex('plunge_stove_top'),
                                                           tex(f'plunge_stove_front{suffix}'), tex('plunge_stove_side')))
        write(f'{bm}/plunge_chiller{suffix}.json', {'parent': 'minecraft:block/cube_column',
                                                    'textures': {'side': tex(f'plunge_chiller_front{suffix}'), 'end': tex('plunge_chiller_top')}})
    write(f'{bs}/plunge_stove.json', facing_states('plunge_stove', 'lit',
                                                    {'false': f'{NS}:block/plunge_stove', 'true': f'{NS}:block/plunge_stove_on'}))
    write(f'{bs}/plunge_chiller.json', {'variants': {
        'powered=false': {'model': f'{NS}:block/plunge_chiller'},
        'powered=true': {'model': f'{NS}:block/plunge_chiller_on'},
    }})

    for suffix in ('', '_active'):
        write(f'{bm}/plunge_thermometer{suffix}.json', orientable('plunge_thermometer', tex('plunge_wall_wood_top'),
                                                                  tex(f'plunge_thermometer_front{suffix}'), tex('plunge_wall_wood')))
    # FACING points into the basin; the dial faces out.
    write(f'{bs}/plunge_thermometer.json', facing_states('plunge_thermometer', 'active',
                                                          {'false': f'{NS}:block/plunge_thermometer',
                                                           'true': f'{NS}:block/plunge_thermometer_active'}, rotate=180))

    write(f'{bm}/sawdust.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex('sawdust')}})
    write(f'{bs}/sawdust.json', {'variants': {'': {'model': f'{NS}:block/sawdust'}}})

    for name in PLUNGE_PARTS:
        write(f'{im}/{name}.json', {'parent': f'{NS}:block/{name}'})

    # items
    write(f'{im}/ladle.json', {
        'parent': 'minecraft:item/handheld',
        'textures': {'layer0': f'{NS}:item/ladle'},
        'overrides': [
            {'predicate': {f'{NS}:filled': 0.5}, 'model': f'{NS}:item/ladle_filled'},
            {'predicate': {f'{NS}:filled': 1.0}, 'model': f'{NS}:item/ladle_hot'},
        ],
    })
    for name in ('ladle_filled', 'ladle_hot', 'bath_broom'):
        write(f'{im}/{name}.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'{NS}:item/{name}'}})
    write(f'{im}/scrub_mitt.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/scrub_mitt'}})


def item(i):
    return {'tag': i[1:]} if i.startswith('#') else {'item': i}


def shaped(name, pattern, key, count=1):
    write(f'data/{NS}/recipe/{name}.json', {
        'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pattern,
        'key': {k: item(v) for k, v in key.items()},
        'result': {'count': count, 'id': f'{NS}:{name}'},
    })


def shapeless(name, ingredients, count=1):
    write(f'data/{NS}/recipe/{name}.json', {
        'type': 'minecraft:crafting_shapeless', 'category': 'misc',
        'ingredients': [item(i) for i in ingredients],
        'result': {'count': count, 'id': f'{NS}:{name}'},
    })


def data():
    blocks = FIREBOXES + STOVE_PARTS + ['chimney'] + APPLIANCES + ['bath_gauge', 'smoke_vent'] + PLUNGE_PARTS
    for name in blocks:
        write(f'data/{NS}/loot_table/blocks/{name}.json', {
            'type': 'minecraft:block',
            'pools': [{
                'rolls': 1.0, 'bonus_rolls': 0.0,
                'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{name}'}],
                'conditions': [{'condition': 'minecraft:survives_explosion'}],
            }],
            'random_sequence': f'{NS}:blocks/{name}',
        })

    write('data/minecraft/tags/block/mineable/pickaxe.json', {'values': [f'{NS}:{n}' for n in
          FIREBOXES + STOVE_PARTS + ['chimney'] + APPLIANCES + ['plunge_stove', 'plunge_chiller', 'plunge_wall_tile']]})
    write('data/minecraft/tags/block/mineable/axe.json', {'values': [f'{NS}:{n}' for n in
          ['bath_gauge', 'smoke_vent', 'plunge_wall_wood', 'plunge_wall_composite', 'plunge_bench', 'plunge_thermometer']]})
    write('data/minecraft/tags/block/mineable/shovel.json', {'values': [f'{NS}:sawdust']})

    def opt(values):
        return [{'id': v, 'required': False} for v in values]

    write(f'data/{NS}/tags/block/bath_wood.json', {'values': opt([
        '#minecraft:logs', '#minecraft:planks', '#minecraft:wooden_slabs', '#minecraft:wooden_stairs',
        '#minecraft:wooden_doors', '#minecraft:wooden_trapdoors', '#minecraft:wooden_fences',
        '#minecraft:fence_gates', '#minecraft:wooden_pressure_plates', 'minecraft:barrel', 'minecraft:bookshelf',
        'minecraft:bamboo_mosaic', 'minecraft:bamboo_mosaic_slab', 'minecraft:bamboo_mosaic_stairs',
        'minecraft:bamboo_block', 'minecraft:stripped_bamboo_block'])})

    stone_families = ['stone', 'cobblestone', 'mossy_cobblestone', 'stone_brick', 'mossy_stone_brick', 'smooth_stone',
                      'andesite', 'polished_andesite', 'diorite', 'polished_diorite', 'granite', 'polished_granite',
                      'cobbled_deepslate', 'polished_deepslate', 'deepslate_brick', 'deepslate_tile',
                      'tuff', 'polished_tuff', 'tuff_brick', 'brick', 'mud_brick', 'sandstone', 'smooth_sandstone',
                      'cut_sandstone', 'red_sandstone', 'smooth_red_sandstone', 'quartz', 'smooth_quartz',
                      'prismarine', 'prismarine_brick', 'dark_prismarine', 'blackstone', 'polished_blackstone',
                      'polished_blackstone_brick', 'nether_brick', 'end_stone_brick', 'purpur']
    stone = ['#minecraft:stone_bricks', '#minecraft:terracotta', 'minecraft:calcite', 'minecraft:quartz_block',
             'minecraft:quartz_bricks', 'minecraft:quartz_pillar', 'minecraft:chiseled_quartz_block',
             'minecraft:stone_bricks', 'minecraft:bricks', 'minecraft:mud_bricks', 'minecraft:deepslate_bricks',
             'minecraft:deepslate_tiles', 'minecraft:tuff_bricks', 'minecraft:prismarine_bricks',
             'minecraft:polished_blackstone_bricks', 'minecraft:nether_bricks', 'minecraft:end_stone_bricks',
             'minecraft:purpur_block', 'minecraft:purpur_pillar', 'minecraft:deepslate', 'minecraft:dripstone_block',
             'minecraft:chiseled_stone_bricks', 'minecraft:chiseled_deepslate', 'minecraft:chiseled_tuff',
             'minecraft:chiseled_tuff_bricks', 'minecraft:chiseled_sandstone', 'minecraft:basalt', 'minecraft:smooth_basalt',
             'minecraft:polished_basalt', 'minecraft:packed_mud']
    for fam in stone_families:
        stone += [f'minecraft:{fam}', f'minecraft:{fam}_slab', f'minecraft:{fam}_stairs', f'minecraft:{fam}_wall']
    for color in ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray',
                  'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']:
        stone += [f'minecraft:{color}_concrete', f'minecraft:{color}_glazed_terracotta']
    write(f'data/{NS}/tags/block/bath_stone.json', {'values': opt(sorted(set(stone)))})
    write(f'data/{NS}/tags/block/bath_marble.json', {'values': opt([
        'minecraft:calcite', 'minecraft:quartz_block', 'minecraft:smooth_quartz', 'minecraft:quartz_bricks',
        'minecraft:quartz_pillar', 'minecraft:chiseled_quartz_block', 'minecraft:quartz_slab', 'minecraft:quartz_stairs',
        'minecraft:smooth_quartz_slab', 'minecraft:smooth_quartz_stairs', 'minecraft:diorite', 'minecraft:polished_diorite',
        'minecraft:polished_diorite_slab', 'minecraft:polished_diorite_stairs', 'minecraft:white_concrete',
        'minecraft:white_glazed_terracotta', 'minecraft:white_terracotta'])})
    write(f'data/{NS}/tags/block/bath_glass.json', {'values': opt([
        '#c:glass_blocks', '#c:glass_panes', 'minecraft:glass', 'minecraft:glass_pane', 'minecraft:tinted_glass'])})
    write(f'data/{NS}/tags/block/bath_insulation.json', {'values': opt(['#minecraft:wool', '#minecraft:wool_carpets'])})

    # heater stones (item tags)
    write(f'data/{NS}/tags/item/heater_stones/excellent.json', {'values': opt([
        'minecraft:smooth_basalt', 'minecraft:basalt', 'minecraft:polished_basalt', 'minecraft:blackstone'])})
    write(f'data/{NS}/tags/item/heater_stones/good.json', {'values': opt([
        'minecraft:deepslate', 'minecraft:cobbled_deepslate', 'minecraft:tuff', 'minecraft:andesite',
        'minecraft:polished_andesite', 'minecraft:stone'])})
    write(f'data/{NS}/tags/item/heater_stones/poor.json', {'values': opt([
        'minecraft:cobblestone', 'minecraft:mossy_cobblestone', 'minecraft:granite', 'minecraft:diorite'])})

    write(f'data/{NS}/damage_type/overheat.json', {
        'exhaustion': 0.1, 'message_id': f'{NS}.overheat', 'scaling': 'never', 'effects': 'burning'})
    for tag in ('is_fire', 'bypasses_armor', 'no_knockback'):
        write(f'data/minecraft/tags/damage_type/{tag}.json', {'values': [f'{NS}:overheat']})

    # recipes: gauge and appliances
    shaped('bath_gauge', ['PGP', 'CRC'], {'P': '#minecraft:planks', 'G': 'minecraft:glass_pane',
                                          'C': 'minecraft:copper_ingot', 'R': 'minecraft:redstone'})
    shaped('steam_generator', ['IGI', 'ICI', 'IRI'], {'I': 'minecraft:iron_ingot', 'G': 'minecraft:glass',
                                                      'C': 'minecraft:cauldron', 'R': 'minecraft:redstone'})
    shaped('warm_lounger', ['QQQ', 'CRC'], {'Q': 'minecraft:calcite', 'C': 'minecraft:copper_ingot', 'R': 'minecraft:redstone'})
    shapeless('smoke_vent', ['#minecraft:wooden_trapdoors', 'minecraft:iron_bars'])
    # stove
    shaped('steel_firebox', [' I ', 'IFI', 'III'], {'I': 'minecraft:iron_ingot', 'F': 'minecraft:furnace'})
    shaped('cast_iron_firebox', ['ICI', 'IFI', 'III'], {'I': 'minecraft:iron_ingot', 'C': '#minecraft:coals', 'F': 'minecraft:furnace'})
    shaped('brick_firebox', ['BBB', 'BFB', 'BBB'], {'B': 'minecraft:bricks', 'F': 'minecraft:furnace'})
    shaped('heating_element', ['III', 'RCR', 'III'], {'I': 'minecraft:iron_ingot', 'R': 'minecraft:redstone', 'C': 'minecraft:copper_ingot'})
    shaped('ash_pit', ['BXB', 'B B', 'BBB'], {'B': 'minecraft:brick', 'X': 'minecraft:iron_bars'})
    shaped('closed_heater', ['BIB', 'I I', 'BIB'], {'B': 'minecraft:bricks', 'I': 'minecraft:iron_ingot'})
    shaped('open_heater', ['X X', 'X X', 'XXX'], {'X': 'minecraft:iron_bars'})
    shaped('boiler', ['I I', 'ICI', 'III'], {'I': 'minecraft:copper_ingot', 'C': 'minecraft:cauldron'})
    shaped('water_tank', ['ICI', 'I I', 'III'], {'I': 'minecraft:iron_ingot', 'C': 'minecraft:copper_ingot'})
    shaped('damper', ['BIB'], {'B': 'minecraft:brick', 'I': 'minecraft:iron_ingot'})
    shaped('fuel_tunnel', ['B B', 'B B', 'B B'], {'B': 'minecraft:bricks'})
    shaped('stove_cladding', ['SC', 'CS'], {'S': 'minecraft:smooth_stone', 'C': 'minecraft:clay_ball'}, count=4)
    shaped('chimney', ['B B', 'B B', 'B B'], {'B': 'minecraft:brick'}, count=4)
    # plunge
    shaped('plunge_wall_wood', ['PIP', 'PIP'], {'P': '#minecraft:planks', 'I': 'minecraft:iron_ingot'}, count=4)
    shaped('plunge_wall_composite', ['PSP', 'PSP'], {'P': '#minecraft:planks', 'S': 'minecraft:slime_ball'}, count=4)
    shaped('plunge_wall_tile', ['TT', 'TT'], {'T': '#minecraft:terracotta'}, count=4)
    shaped('plunge_bench', ['PPP', 'S S'], {'P': '#minecraft:planks', 'S': 'minecraft:stick'}, count=2)
    shaped('plunge_stove', [' I ', 'IFI', ' I '], {'I': 'minecraft:iron_ingot', 'F': 'minecraft:furnace'})
    shaped('plunge_chiller', ['IRI', 'IPI', 'III'], {'I': 'minecraft:iron_ingot', 'R': 'minecraft:redstone', 'P': 'minecraft:packed_ice'})
    shapeless('plunge_thermometer', [f'{NS}:plunge_wall_wood', 'minecraft:glass_pane', 'minecraft:redstone'])
    shapeless('sawdust', ['#minecraft:logs'], count=8)
    # tools
    shapeless('ladle', ['minecraft:bowl', 'minecraft:stick'])
    shapeless('bath_broom', ['#minecraft:leaves', '#minecraft:leaves', '#minecraft:leaves', 'minecraft:stick', 'minecraft:string'])
    shapeless('scrub_mitt', ['#minecraft:wool', 'minecraft:leather', 'minecraft:string'])

    write('pack.mcmeta', {'pack': {'description': 'Bathhouse resources', 'pack_format': 34}})


if __name__ == '__main__':
    import shutil
    for d in ('assets/bathhouse/blockstates', 'assets/bathhouse/models', 'data/bathhouse/recipe',
              'data/bathhouse/loot_table', 'data/bathhouse/tags', 'data/minecraft'):
        shutil.rmtree(os.path.join(ROOT, d), ignore_errors=True)
    assets()
    data()
    print('resources written to', os.path.normpath(ROOT))
