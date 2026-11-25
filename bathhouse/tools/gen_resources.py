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

APPLIANCES = ['inner_heater', 'outer_heater', 'brick_stove', 'iron_stove', 'electric_heater', 'steam_generator', 'warm_lounger']
PLUNGES = {'cold_plunge': 'water', 'hot_plunge': 'water', 'sawdust_plunge': 'sawdust'}


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


def assets():
    a = f'assets/{NS}'
    # appliances: orientable cubes with a lit front
    for name in APPLIANCES:
        variants = {}
        for facing, y in FACINGS.items():
            for lit in ('false', 'true'):
                model = f'{NS}:block/{name}' + ('_on' if lit == 'true' else '')
                variants[f'facing={facing},lit={lit}'] = variant(model, y)
        write(f'{a}/blockstates/{name}.json', {'variants': variants})
        for suffix in ('', '_on'):
            write(f'{a}/models/block/{name}{suffix}.json', {
                'parent': 'minecraft:block/orientable',
                'textures': {
                    'top': f'{NS}:block/{name}_top',
                    'front': f'{NS}:block/{name}_front{suffix}',
                    'side': f'{NS}:block/{name}_side',
                },
            })
        write(f'{a}/models/item/{name}.json', {'parent': f'{NS}:block/{name}'})

    # thermo-hygrometer: thin plate on the wall
    variants = {}
    for facing, y in FACINGS.items():
        for active in ('false', 'true'):
            model = f'{NS}:block/bath_gauge' + ('_active' if active == 'true' else '')
            variants[f'active={active},facing={facing}'] = variant(model, y)
    write(f'{a}/blockstates/bath_gauge.json', {'variants': variants})
    for suffix in ('', '_active'):
        write(f'{a}/models/block/bath_gauge{suffix}.json', {
            'parent': 'minecraft:block/block',
            'textures': {
                'particle': f'{NS}:block/bath_gauge_side',
                'front': f'{NS}:block/bath_gauge_front{suffix}',
                'side': f'{NS}:block/bath_gauge_side',
            },
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
    write(f'{a}/models/item/bath_gauge.json', {'parent': 'minecraft:item/generated',
                                              'textures': {'layer0': f'{NS}:block/bath_gauge_front'}})

    # chimney: brick pillar
    write(f'{a}/blockstates/chimney.json', {'variants': {'': {'model': f'{NS}:block/chimney'}}})
    write(f'{a}/models/block/chimney.json', {
        'parent': 'minecraft:block/block',
        'textures': {'particle': f'{NS}:block/chimney', 'side': f'{NS}:block/chimney', 'top': f'{NS}:block/chimney_top'},
        'elements': [{
            'from': [4, 0, 4], 'to': [12, 16, 12],
            'faces': {
                'north': {'texture': '#side'}, 'south': {'texture': '#side'},
                'east': {'texture': '#side'}, 'west': {'texture': '#side'},
                'up': {'texture': '#top', 'cullface': 'up'}, 'down': {'texture': '#top', 'cullface': 'down'},
            },
        }],
    })
    write(f'{a}/models/item/chimney.json', {'parent': f'{NS}:block/chimney'})

    # smoke window
    write(f'{a}/blockstates/smoke_vent.json', {'variants': {
        'open=false': {'model': f'{NS}:block/smoke_vent'},
        'open=true': {'model': f'{NS}:block/smoke_vent_open'},
    }})
    for suffix in ('', '_open'):
        write(f'{a}/models/block/smoke_vent{suffix}.json', {
            'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/smoke_vent{suffix}'}})
    write(f'{a}/models/item/smoke_vent.json', {'parent': f'{NS}:block/smoke_vent'})

    # plunge tubs: one template per content level
    def wall(frm, to, outer, inner_dir):
        faces = {}
        for d in ('north', 'south', 'east', 'west'):
            if d == outer:
                faces[d] = {'texture': '#front' if outer == 'north' else '#side', 'cullface': d}
            elif d == inner_dir:
                faces[d] = {'texture': '#inner'}
            else:
                faces[d] = {'texture': '#side'}
        faces['up'] = {'texture': '#rim'}
        faces['down'] = {'texture': '#bottom', 'cullface': 'down'}
        return {'from': frm, 'to': to, 'faces': faces}

    walls = [
        wall([0, 0, 0], [16, 16, 1], 'north', 'south'),
        wall([0, 0, 15], [16, 16, 16], 'south', 'north'),
        wall([0, 0, 1], [1, 16, 15], 'west', 'east'),
        wall([15, 0, 1], [16, 16, 15], 'east', 'west'),
        {'from': [1, 0, 1], 'to': [15, 3, 15], 'faces': {
            'up': {'texture': '#inner'}, 'down': {'texture': '#bottom', 'cullface': 'down'}}},
        # inner bench
        {'from': [1, 3, 11], 'to': [15, 7, 15], 'faces': {
            'up': {'texture': '#rim'}, 'north': {'texture': '#inner'}}},
    ]
    heights = {1: 6, 2: 11, 3: 15}
    for level in range(4):
        elements = list(walls)
        if level:
            elements.append({'from': [1, 3, 1], 'to': [15, heights[level], 15], 'faces': {
                'up': {'texture': '#content', 'tintindex': 0}}})
        write(f'{a}/models/block/plunge_template_{level}.json', {
            'parent': 'minecraft:block/block',
            'ambientocclusion': False,
            'textures': {'particle': '#side'},
            'elements': elements,
        })

    for name, medium in PLUNGES.items():
        side = f'{NS}:block/sawdust_plunge_side' if name == 'sawdust_plunge' else f'{NS}:block/plunge_side'
        content = 'minecraft:block/water_still' if medium == 'water' else f'{NS}:block/sawdust'
        heated = name != 'cold_plunge'
        variants = {}
        for lit in (('false', 'true') if heated else ('false',)):
            for level in range(4):
                model = f'{NS}:block/{name}_{level}' + ('_on' if lit == 'true' else '')
                textures = {
                    'side': side,
                    'front': (f'{NS}:block/{name}_front' + ('_on' if lit == 'true' else '')) if heated else side,
                    'inner': f'{NS}:block/plunge_inner',
                    'rim': f'{NS}:block/plunge_rim',
                    'bottom': f'{NS}:block/plunge_bottom',
                    'content': content,
                }
                write(f'{a}/models/block/{name}_{level}' + ('_on' if lit == 'true' else '') + '.json', {
                    'parent': f'{NS}:block/plunge_template_{level}', 'textures': textures})
        for facing, y in FACINGS.items():
            for level in range(4):
                for lit in ('false', 'true'):
                    for warm in ('false', 'true'):
                        on = heated and lit == 'true'
                        model = f'{NS}:block/{name}_{level}' + ('_on' if on else '')
                        variants[f'facing={facing},level={level},lit={lit},warm={warm}'] = variant(model, y)
        write(f'{a}/blockstates/{name}.json', {'variants': variants})
        write(f'{a}/models/item/{name}.json', {'parent': f'{NS}:block/{name}_2'})

    # items
    write(f'{a}/models/item/ladle.json', {
        'parent': 'minecraft:item/handheld',
        'textures': {'layer0': f'{NS}:item/ladle'},
        'overrides': [{'predicate': {f'{NS}:filled': 1}, 'model': f'{NS}:item/ladle_filled'}],
    })
    write(f'{a}/models/item/ladle_filled.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'{NS}:item/ladle_filled'}})
    write(f'{a}/models/item/bath_broom.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'{NS}:item/bath_broom'}})
    write(f'{a}/models/item/scrub_mitt.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/scrub_mitt'}})
    write(f'{a}/models/item/sawdust.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/sawdust'}})


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
    blocks = APPLIANCES + ['bath_gauge', 'chimney', 'smoke_vent'] + list(PLUNGES)
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

    write('data/minecraft/tags/block/mineable/pickaxe.json',
          {'values': [f'{NS}:{n}' for n in APPLIANCES + ['chimney']]})
    write('data/minecraft/tags/block/mineable/axe.json',
          {'values': [f'{NS}:{n}' for n in ['bath_gauge', 'smoke_vent'] + list(PLUNGES)]})

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
    write(f'data/{NS}/tags/block/bath_glass.json', {'values': opt([
        '#c:glass_blocks', '#c:glass_panes', 'minecraft:glass', 'minecraft:glass_pane', 'minecraft:tinted_glass'])})
    write(f'data/{NS}/tags/block/bath_insulation.json', {'values': opt(['#minecraft:wool', '#minecraft:wool_carpets'])})

    write(f'data/{NS}/damage_type/overheat.json', {
        'exhaustion': 0.1, 'message_id': f'{NS}.overheat', 'scaling': 'never', 'effects': 'burning'})
    for tag in ('is_fire', 'bypasses_armor', 'no_knockback'):
        write(f'data/minecraft/tags/damage_type/{tag}.json', {'values': [f'{NS}:overheat']})

    # recipes
    shaped('bath_gauge', ['PGP', 'CRC'], {'P': '#minecraft:planks', 'G': 'minecraft:glass_pane',
                                          'C': 'minecraft:copper_ingot', 'R': 'minecraft:redstone'})
    shaped('inner_heater', ['SSS', 'I I', 'IFI'], {'S': 'minecraft:cobblestone', 'I': 'minecraft:iron_ingot', 'F': 'minecraft:furnace'})
    shaped('outer_heater', ['SSS', 'BFB', 'BBB'], {'S': 'minecraft:cobblestone', 'B': 'minecraft:bricks', 'F': 'minecraft:furnace'})
    shaped('brick_stove', ['BBB', 'BFB', 'BSB'], {'S': 'minecraft:cobblestone', 'B': 'minecraft:bricks', 'F': 'minecraft:furnace'})
    shaped('iron_stove', [' I ', 'IFI', 'III'], {'I': 'minecraft:iron_ingot', 'F': 'minecraft:furnace'})
    shaped('electric_heater', ['SSS', 'IRI', 'IRI'], {'S': 'minecraft:cobblestone', 'I': 'minecraft:iron_ingot', 'R': 'minecraft:redstone'})
    shaped('steam_generator', ['IGI', 'ICI', 'IRI'], {'I': 'minecraft:iron_ingot', 'G': 'minecraft:glass',
                                                      'C': 'minecraft:cauldron', 'R': 'minecraft:redstone'})
    shaped('warm_lounger', ['QQQ', 'CRC'], {'Q': 'minecraft:calcite', 'C': 'minecraft:copper_ingot', 'R': 'minecraft:redstone'})
    shaped('chimney', ['B B', 'B B', 'B B'], {'B': 'minecraft:brick'}, count=4)
    shaped('cold_plunge', ['I I', 'P P', 'PPP'], {'I': 'minecraft:iron_ingot', 'P': '#minecraft:planks'})
    shapeless('smoke_vent', ['#minecraft:wooden_trapdoors', 'minecraft:iron_bars'])
    shapeless('ladle', ['minecraft:bowl', 'minecraft:stick'])
    shapeless('bath_broom', ['#minecraft:leaves', '#minecraft:leaves', '#minecraft:leaves', 'minecraft:stick', 'minecraft:string'])
    shapeless('scrub_mitt', ['#minecraft:wool', 'minecraft:leather', 'minecraft:string'])
    shapeless('sawdust', ['#minecraft:logs'], count=8)
    shapeless('hot_plunge', [f'{NS}:cold_plunge', 'minecraft:furnace', 'minecraft:iron_ingot'])
    shapeless('sawdust_plunge', [f'{NS}:cold_plunge', 'minecraft:furnace', f'{NS}:sawdust'])

    write('pack.mcmeta', {'pack': {'description': 'Bathhouse resources', 'pack_format': 34}})


if __name__ == '__main__':
    assets()
    data()
    print('resources written to', os.path.normpath(ROOT))
